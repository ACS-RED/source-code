package com.jeoktoma;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RaceController {

    @GetMapping("/")
    public String root() {
        return "forward:/index.html";
    }

    @Value("${app.version}")
    private String appVersion;

    @Autowired
    private RaceService raceService;

    @GetMapping("/status")
    public ResponseEntity<RaceResponse> getStatus() {
        return ResponseEntity.ok(raceService.getRaceStatus());
    }

    @PostMapping("/bet")
    public ResponseEntity<Map<String, Object>> placeBet(@RequestBody Map<String, Object> betData) {
        int userId = (Integer) betData.get("userId");
        int horseId = (Integer) betData.get("horseId");
        int amount = (Integer) betData.get("amount");
        
        Map<String, Object> result = raceService.placeBetWithUserUpdate(userId, horseId, amount);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/user/{id}")
    public ResponseEntity<?> getUser(@PathVariable int id) {
        try {
            User user = raceService.getUser(id);
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            return ResponseEntity.status(404).body("사용자를 찾을 수 없습니다.");
        }
    }
    
    @GetMapping("/ranking")
    public ResponseEntity<?> getRanking() {
        try {
            return ResponseEntity.ok(raceService.getRanking());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("랭킹 조회 실패: " + e.getMessage());
        }
    }
    
    @PostMapping("/user/create")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> data) {
        try {
            String username = data.get("username");
            if (username == null || username.trim().isEmpty()) {
                username = "사용자" + System.currentTimeMillis();
            }
            User user = raceService.createUser(username.trim());
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("사용자 생성 실패: " + e.getMessage());
        }
    }
    
    @GetMapping("/betting-totals")
    public ResponseEntity<Map<String, Object>> getBettingTotals() {
        try {
            return ResponseEntity.ok(raceService.getBettingTotals());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }
    
    @GetMapping("/user/{userId}/bets")
    public ResponseEntity<Map<String, Object>> getUserBets(@PathVariable Long userId) {
        try {
            return ResponseEntity.ok(raceService.getUserBets(userId));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> getVersion(HttpServletRequest request) {
        String serverInfo = request.getServletContext().getServerInfo();
        
        // Nginx에서 넘겨준 헤더 확인
        String nginxVersion = request.getHeader("X-Nginx-Version");
        if (nginxVersion == null || nginxVersion.isEmpty()) {
            nginxVersion = "Nginx"; // 헤더가 없으면 기본 텍스트 표시
        } else {
            nginxVersion = "Nginx/" + nginxVersion; // 버전이 있으면 붙여서 표시
        }
        
        Map<String, String> response = Map.of(
            "appVersion", appVersion,
            "serverInfo", serverInfo,
            "nginxVersion", nginxVersion
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/race/clear-deleted-user")
    public ResponseEntity<Void> clearDeletedUser() {
        raceService.clearDeletedUser();
        return ResponseEntity.ok().build();
    }
}