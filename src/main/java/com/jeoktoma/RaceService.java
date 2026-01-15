package com.jeoktoma;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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
    // raceRunning 변수 삭제: 상태는 오직 DB만 믿는다 (Stateless)
    private final String serverId = java.util.UUID.randomUUID().toString().substring(0, 8);
    
    @PostConstruct
    public void init() {
        startGlobalTimer();
    }
    
    private void startGlobalTimer() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateGlobalTimer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
    
    // 트랜잭션 적용: 리더 선출과 로직 수행의 원자성 보장 시도
    @Transactional
    public void updateGlobalTimer() {
        // 리더 선출 및 생존 신고 (3초로 단축하여 Failover 속도 향상)
        int updatedRows = jdbcTemplate.update(
            "UPDATE race_status SET leader_ip = ?, last_heartbeat = NOW() " +
            "WHERE id = 1 AND (leader_ip IS NULL OR last_heartbeat < DATE_SUB(NOW(), INTERVAL 3 SECOND) OR leader_ip = ?)",
            serverId, serverId
        );

        // 리더가 아니면 아무것도 안 함 (철저한 Follower 역할)
        if (updatedRows == 0) {
            return;
        }

        Map<String, Object> status = jdbcTemplate.queryForMap("SELECT * FROM race_status WHERE id = 1");
        String currentStatus = (String) status.get("status");
        int currentTimer = (Integer) status.get("timer");
        
        if ("READY".equals(currentStatus) && currentTimer > 0) {
            jdbcTemplate.update("UPDATE race_status SET timer = timer - 1 WHERE id = 1");
        } else if ("READY".equals(currentStatus) && currentTimer <= 0) {
            startRace();
        } else if ("RACING".equals(currentStatus)) {
            // 레이싱 중이면 무조건 타이머 감소 및 말 이동 처리 (로컬 변수 확인 X)
            if (currentTimer > 0) {
                jdbcTemplate.update("UPDATE race_status SET timer = timer - 1 WHERE id = 1");
            }
            updateRace(); // 상태 기반으로 동작하므로 인자 불필요
        } else if ("FINISHED".equals(currentStatus)) {
            // FINISHED 상태에서도 타이머를 감소시켜 10초 대기 후 리셋
            if (currentTimer > 0) {
                jdbcTemplate.update("UPDATE race_status SET timer = timer - 1 WHERE id = 1");
            } else {
                resetRace();
            }
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
        response.setDeletedUser((String) status.get("deleted_user"));
        response.setServerIp("localhost");
        response.setHorses(horses);
        
        return response;
    }

    @Transactional
    public void startRace() {
        // DB 상태만 변경
        jdbcTemplate.update("UPDATE race_status SET status = 'RACING', timer = 10 WHERE id = 1");
        jdbcTemplate.update("UPDATE horses SET position = 0");
    }
    
    @Transactional
    public void updateRace() {
        // 더 이상 raceRunning 변수를 확인하지 않음. 호출되었다는 것 자체가 RACING 상태임.
        
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
            int variance = random.nextInt(81) - 40; 
            int moveDistance = horse.getSpeed() + variance;
            
            int newPosition = horse.getPosition() + moveDistance;
            if (newPosition < 0) newPosition = 0;
            
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
    
    @Transactional
    public void finishRace(String winner) {
        // 상태 변경과 정산을 한 트랜잭션으로 묶음. 
        // FINISHED 상태에서 10초 카운트다운 시작 (Stateless 리셋을 위함)
        jdbcTemplate.update("UPDATE race_status SET status = 'FINISHED', winner_horse = ?, timer = 10 WHERE id = 1", winner);
        
        payoutWinners(winner);
        
        String topWinner = getTopWinner(winner);
        jdbcTemplate.update("UPDATE race_status SET top_winner = ? WHERE id = 1", topWinner);
        
        // 스케줄러 제거: updateGlobalTimer가 타이머 0 되면 리셋함
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
        
        checkAndDeleteBrokeUsers();
    }
    
    private void checkAndDeleteBrokeUsers() {
        List<Map<String, Object>> brokeUsers = jdbcTemplate.queryForList(
            "SELECT DISTINCT u.id, u.username FROM users u " +
            "JOIN bets b ON u.id = b.user_id " +
            "WHERE u.points = 0");
        
        for (Map<String, Object> user : brokeUsers) {
            int userId = (Integer) user.get("id");
            String username = (String) user.get("username");
            
            jdbcTemplate.update("DELETE FROM bets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
            
            jdbcTemplate.update(
                "UPDATE race_status SET deleted_user = ? WHERE id = 1", 
                username + "님이 파산하여 계정이 삭제되었습니다.");
        }
    }
    
    @Transactional
    public void resetRace() {
        Random random = new Random();
        int speed1 = 40 + random.nextInt(31);
        int speed2 = 40 + random.nextInt(31);
        int speed3 = 40 + random.nextInt(31);
        int speed4 = 40 + random.nextInt(31);
        
        jdbcTemplate.update("UPDATE horses SET speed = ? WHERE id = 1", speed1);
        jdbcTemplate.update("UPDATE horses SET speed = ? WHERE id = 2", speed2);
        jdbcTemplate.update("UPDATE horses SET speed = ? WHERE id = 3", speed3);
        jdbcTemplate.update("UPDATE horses SET speed = ? WHERE id = 4", speed4);
        
        jdbcTemplate.update("UPDATE race_status SET status = 'READY', timer = 30, winner_horse = NULL, top_winner = NULL, deleted_user = NULL WHERE id = 1");
        jdbcTemplate.update("UPDATE horses SET position = 0");
        jdbcTemplate.update("DELETE FROM bets");
    }
    
    public String placeBet(int userId, int horseId, int amount) {
        int updatedRows = jdbcTemplate.update(
            "UPDATE users SET points = points - ? WHERE id = ? AND points >= ?", 
            amount, userId, amount
        );

        if (updatedRows == 0) {
            return "포인트가 부족하거나 처리 중 오류가 발생했습니다!";
        }
        
        jdbcTemplate.update("INSERT INTO bets (user_id, horse_id, amount) VALUES (?, ?, ?)", 
            userId, horseId, amount);
        
        return "베팅이 완료되었습니다!";
    }
    
    public Map<String, Object> placeBetWithUserUpdate(int userId, int horseId, int amount) {
        Map<String, Object> result = new HashMap<>();
        
        int updatedRows = jdbcTemplate.update(
            "UPDATE users SET points = points - ? WHERE id = ? AND points >= ?", 
            amount, userId, amount
        );

        if (updatedRows == 0) {
            result.put("success", false);
            result.put("message", "포인트가 부족하거나 처리 중 오류가 발생했습니다!");
            return result;
        }
        
        jdbcTemplate.update("INSERT INTO bets (user_id, horse_id, amount) VALUES (?, ?, ?)", 
            userId, horseId, amount);
        
        User updatedUser = getUser(userId);
        result.put("success", true);
        result.put("message", "베팅이 완료되었습니다!");
        result.put("user", updatedUser);
        
        return result;
    }
    
    public List<Map<String, Object>> getRanking() {
        return jdbcTemplate.queryForList("SELECT username, points FROM users ORDER BY points DESC LIMIT 10");
    }
    
    @Transactional
    public User createUser(String username) {
        try {
            // 1. 기존 유저가 있는지 확인 (로그인)
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE username = ?", username);
            User user = new User();
            user.setId((Integer) row.get("id"));
            user.setUsername((String) row.get("username"));
            user.setPoints((Integer) row.get("points"));
            return user;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // 2. 없으면 신규 생성 (회원가입)
            jdbcTemplate.update("INSERT INTO users (username, points) VALUES (?, ?)", username, 1000);
            
            // 방금 생성된 유저 정보 다시 조회
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE username = ?", username);
            User user = new User();
            user.setId((Integer) row.get("id"));
            user.setUsername((String) row.get("username"));
            user.setPoints((Integer) row.get("points"));
            return user;
        }
    }
    
    public User getUser(int id) {
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", id);
        User user = new User();
        user.setId((Integer) row.get("id"));
        user.setUsername((String) row.get("username"));
        user.setPoints((Integer) row.get("points"));
        return user;
    }

    public Map<String, Object> getBettingTotals() {
        List<Map<String, Object>> totals = jdbcTemplate.queryForList(
            "SELECT h.id, h.name, COALESCE(SUM(b.amount), 0) as total " +
            "FROM horses h LEFT JOIN bets b ON h.id = b.horse_id " +
            "GROUP BY h.id, h.name ORDER BY h.id");
        
        Map<String, Object> result = new HashMap<>();
        for (Map<String, Object> row : totals) {
            result.put(String.valueOf(row.get("id")), row.get("total"));
        }
        return result;
    }
    
    public Map<String, Object> getUserBets(Long userId) {
        List<Map<String, Object>> userBets = jdbcTemplate.queryForList(
            "SELECT horse_id, SUM(amount) as total " +
            "FROM bets WHERE user_id = ? " +
            "GROUP BY horse_id", userId);
        
        Map<String, Object> result = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            result.put(String.valueOf(i), 0);
        }
        
        for (Map<String, Object> row : userBets) {
            result.put(String.valueOf(row.get("horse_id")), row.get("total"));
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