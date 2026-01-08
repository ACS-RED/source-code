package com.jeoktoma;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class RaceService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private boolean raceRunning = false;
    private boolean timerRunning = false;
    
    @PostConstruct
    public void init() {
        // 의존성 주입 완료 후 타이머 시작
        startGlobalTimer();
    }
    
    private void startGlobalTimer() {
        if (timerRunning) return;
        timerRunning = true;
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateGlobalTimer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
    
    private void updateGlobalTimer() {
        Map<String, Object> status = jdbcTemplate.queryForMap("SELECT * FROM race_status WHERE id = 1");
        String currentStatus = (String) status.get("status");
        int currentTimer = (Integer) status.get("timer");
        
        if ("READY".equals(currentStatus) && currentTimer > 0) {
            // READY 상태에서 타이머 감소
            jdbcTemplate.update("UPDATE race_status SET timer = timer - 1 WHERE id = 1");
        } else if ("READY".equals(currentStatus) && currentTimer <= 0) {
            // 타이머가 0이 되면 경주 시작
            startRace();
        } else if ("RACING".equals(currentStatus) && currentTimer > 0) {
            // RACING 상태에서도 타이머 감소
            jdbcTemplate.update("UPDATE race_status SET timer = timer - 1 WHERE id = 1");
        }
    }
    
    public RaceResponse getRaceStatus() {
        Map<String, Object> status = jdbcTemplate.queryForMap("SELECT * FROM race_status WHERE id = 1");
        List<Horse> horses = jdbcTemplate.query("SELECT * FROM horses ORDER BY id", 
            (rs, rowNum) -> {
                Horse horse = new Horse();
                horse.setId(rs.getInt("id"));
                horse.setName(rs.getString("name"));
                horse.setSpeed(rs.getInt("speed"));
                horse.setPosition(rs.getInt("position"));
                return horse;
            });
        
        RaceResponse response = new RaceResponse();
        response.setStatus((String) status.get("status"));
        response.setTimer((Integer) status.get("timer"));
        response.setWinnerHorse((String) status.get("winner_horse"));
        response.setTopWinner((String) status.get("top_winner"));
        response.setServerIp("localhost");
        response.setHorses(horses);
        
        return response;
    }

    public void startRace() {
        if (raceRunning) return;
        
        raceRunning = true;
        jdbcTemplate.update("UPDATE race_status SET status = 'RACING', timer = 10 WHERE id = 1");
        jdbcTemplate.update("UPDATE horses SET position = 0");
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateRace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
    
    private void updateRace() {
        List<Horse> horses = jdbcTemplate.query("SELECT * FROM horses", 
            (rs, rowNum) -> {
                Horse horse = new Horse();
                horse.setId(rs.getInt("id"));
                horse.setName(rs.getString("name"));
                horse.setSpeed(rs.getInt("speed"));
                horse.setPosition(rs.getInt("position"));
                return horse;
            });
        
        Random random = new Random();
        boolean raceFinished = false;
        String winner = null;
        
        for (Horse horse : horses) {
            int moveDistance = horse.getSpeed() + random.nextInt(20) - 10;
            int newPosition = horse.getPosition() + moveDistance;
            
            if (newPosition >= 1000) {
                newPosition = 1000;
                if (!raceFinished) {
                    raceFinished = true;
                    winner = horse.getName();
                }
            }
            
            jdbcTemplate.update("UPDATE horses SET position = ? WHERE id = ?", 
                newPosition, horse.getId());
        }
        
        if (raceFinished) {
            finishRace(winner);
        }
    }
    
    private void finishRace(String winner) {
        raceRunning = false;
        timerRunning = false; // 타이머 상태 리셋
        scheduler.shutdown();
        scheduler = Executors.newScheduledThreadPool(1);
        
        jdbcTemplate.update("UPDATE race_status SET status = 'FINISHED', winner_horse = ? WHERE id = 1", winner);
        
        payoutWinners(winner);
        
        // topWinner 정보를 race_status 테이블에 저장
        String topWinner = getTopWinner(winner);
        jdbcTemplate.update("UPDATE race_status SET top_winner = ? WHERE id = 1", topWinner);
        
        scheduler.schedule(() -> {
            resetRace();
            startGlobalTimer(); // 리셋 후 타이머 재시작
        }, 10, TimeUnit.SECONDS);
    }
    
    private void payoutWinners(String winnerHorse) {
        List<Map<String, Object>> winningBets = jdbcTemplate.queryForList(
            "SELECT b.user_id, b.amount FROM bets b JOIN horses h ON b.horse_id = h.id WHERE h.name = ?", 
            winnerHorse);
        
        for (Map<String, Object> bet : winningBets) {
            int userId = (Integer) bet.get("user_id");
            int amount = (Integer) bet.get("amount");
            int payout = amount * 2;
            
            jdbcTemplate.update("UPDATE users SET points = points + ? WHERE id = ?", payout, userId);
        }
    }
    
    private void resetRace() {
        // 말의 속도를 랜덤하게 변경 (40-70 범위)
        Random random = new Random();
        int speed1 = 40 + random.nextInt(31);
        int speed2 = 40 + random.nextInt(31);
        int speed3 = 40 + random.nextInt(31);
        
        System.out.println("resetRace() called - New speeds: 적토마=" + speed1 + ", 청토마=" + speed2 + ", 백토마=" + speed3);
        
        jdbcTemplate.update("UPDATE horses SET speed = ? WHERE id = 1", speed1);
        jdbcTemplate.update("UPDATE horses SET speed = ? WHERE id = 2", speed2);
        jdbcTemplate.update("UPDATE horses SET speed = ? WHERE id = 3", speed3);
        
        jdbcTemplate.update("UPDATE race_status SET status = 'READY', timer = 60, winner_horse = NULL, top_winner = NULL WHERE id = 1");
        jdbcTemplate.update("UPDATE horses SET position = 0");
        jdbcTemplate.update("DELETE FROM bets");
    }
    
    public String placeBet(int userId, int horseId, int amount) {
        Integer userPoints = jdbcTemplate.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
        if (userPoints == null || userPoints < amount) {
            return "포인트가 부족합니다!";
        }
        
        jdbcTemplate.update("INSERT INTO bets (user_id, horse_id, amount) VALUES (?, ?, ?)", 
            userId, horseId, amount);
        jdbcTemplate.update("UPDATE users SET points = points - ? WHERE id = ?", amount, userId);
        
        return "베팅이 완료되었습니다!";
    }
    
    public List<Map<String, Object>> getRanking() {
        return jdbcTemplate.queryForList("SELECT username, points FROM users ORDER BY points DESC LIMIT 10");
    }
    
    public User createUser(String username) {
        jdbcTemplate.update("INSERT INTO users (username, points) VALUES (?, ?)", username, 1000);
        Integer userId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
        
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPoints(1000);
        return user;
    }
    
    public User getUser(int id) {
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", id);
        User user = new User();
        user.setId((Integer) row.get("id"));
        user.setUsername((String) row.get("username"));
        user.setPoints((Integer) row.get("points"));
        return user;
    }
    
    public void updateUsername(int userId, String newUsername) {
        jdbcTemplate.update("UPDATE users SET username = ? WHERE id = ?", newUsername, userId);
    }
    
    public Map<String, Object> getBettingTotals() {
        List<Map<String, Object>> totals = jdbcTemplate.queryForList(
            "SELECT h.id, h.name, COALESCE(SUM(b.amount), 0) as total " +
            "FROM horses h LEFT JOIN bets b ON h.id = b.horse_id " +
            "GROUP BY h.id, h.name ORDER BY h.id");
        
        Map<String, Object> result = new HashMap<>();
        for (Map<String, Object> row : totals) {
            result.put("horse" + row.get("id"), row.get("total"));
        }
        return result;
    }
    
    public String getTopWinner(String winnerHorse) {
        List<Map<String, Object>> winners = jdbcTemplate.queryForList(
            "SELECT u.username, b.amount * 2 as payout " +
            "FROM bets b JOIN horses h ON b.horse_id = h.id " +
            "JOIN users u ON b.user_id = u.id " +
            "WHERE h.name = ? ORDER BY b.amount DESC LIMIT 1", 
            winnerHorse);
        
        if (winners.isEmpty()) {
            return "베팅한 사용자가 없습니다";
        }
        
        Map<String, Object> topWinner = winners.get(0);
        return topWinner.get("username") + " (" + topWinner.get("payout") + "P 획득)";
    }
}
