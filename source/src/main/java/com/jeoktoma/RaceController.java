package com.jeoktoma;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RaceController {

    @Autowired
    private RaceService raceService;

    @GetMapping("/status")
    public ResponseEntity<RaceResponse> getStatus() {
        return ResponseEntity.ok(raceService.getRaceStatus());
    }

    @PostMapping("/bet")
    public ResponseEntity<String> placeBet(@RequestBody Map<String, Object> betData) {
        int userId = (Integer) betData.get("userId");
        int horseId = (Integer) betData.get("horseId");
        int amount = (Integer) betData.get("amount");
        
        String result = raceService.placeBet(userId, horseId, amount);
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
    
    @PostMapping("/user/{id}/name")
    public ResponseEntity<String> changeUsername(@PathVariable int id, @RequestBody Map<String, String> data) {
        try {
            String newName = data.get("username");
            raceService.updateUsername(id, newName);
            return ResponseEntity.ok("이름이 변경되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("이름 변경 실패: " + e.getMessage());
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
}
