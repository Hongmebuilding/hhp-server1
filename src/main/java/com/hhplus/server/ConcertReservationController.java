package com.hhplus.server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1")
public class ConcertReservationController {

    private final Map<String, AuthToken> authTokens = new ConcurrentHashMap<>();
    private final Map<String, QueueToken> queueTokens = new ConcurrentHashMap<>();
    private final Map<String, Integer> userBalances = new ConcurrentHashMap<>();
    private final Map<String, List<Integer>> availableSeats = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, String>> reservations = new ConcurrentHashMap<>();

    // 유저 토큰 발급 API
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> issueToken() {
        String uuid = UUID.randomUUID().toString();
        AuthToken authToken = new AuthToken(uuid, System.currentTimeMillis());
        QueueToken queueToken = new QueueToken(uuid, 1, "wait", System.currentTimeMillis());
        authTokens.put(uuid, authToken);
        queueTokens.put(uuid, queueToken);

        Map<String, Object> response = new HashMap<>();
        response.put("token", uuid);
        response.put("seq", 1);
        response.put("status", "wait");
        return ResponseEntity.ok(response);
    }

    // 대기열 상태 확인 API
    @GetMapping("/queue/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus(@RequestHeader("Authorization") String token) {
        if (!validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        QueueToken queueToken = queueTokens.get(token);
        Map<String, Object> response = new HashMap<>();
        response.put("seq", queueToken.getSeq());
        response.put("status", queueToken.getStatus());
        return ResponseEntity.ok(response);
    }

    // 대기열 처리 API (서버 내부용)
    private void processNextInQueue() {
        Optional<QueueToken> nextInQueue = queueTokens.values().stream()
                .filter(qt -> "wait".equals(qt.getStatus()))
                .min(Comparator.comparingInt(QueueToken::getSeq));

        nextInQueue.ifPresent(qt -> {
            qt.setStatus("proceeding");
            queueTokens.values().stream()
                    .filter(otherQt -> otherQt.getSeq() > qt.getSeq())
                    .forEach(otherQt -> otherQt.setSeq(otherQt.getSeq() - 1));
        });
    }

    // 예약 가능 날짜 API
    @GetMapping("/dates")
    public ResponseEntity<List<String>> getAvailableDates(@RequestHeader("Authorization") String token) {
        if (!validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<String> dates = Arrays.asList("2024-07-15", "2024-07-16", "2024-07-17");
        return ResponseEntity.ok(dates);
    }

    // 예약 가능 좌석 API
    @GetMapping("/seats")
    public ResponseEntity<List<Integer>> getAvailableSeats(@RequestHeader("Authorization") String token,
                                                           @RequestParam String date) {
        if (!validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<Integer> seats = availableSeats.computeIfAbsent(date, k -> {
            List<Integer> newSeats = new ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                newSeats.add(i);
            }
            return newSeats;
        });

        return ResponseEntity.ok(seats);
    }

    // 좌석 예약 요청 API
    @PostMapping("/reservations")
    public ResponseEntity<Map<String, Object>> reserveSeat(@RequestHeader("Authorization") String token,
                                                           @RequestBody Map<String, Object> request) {
        if (!validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        QueueToken queueToken = queueTokens.get(token);
        if (!"proceeding".equals(queueToken.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "순서를 지키십쇼"));
        }

        String date = (String) request.get("date");
        Integer seatNumber = (Integer) request.get("seatNumber");

        if (!availableSeats.get(date).contains(seatNumber)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Sold out"));
        }

        availableSeats.get(date).remove(seatNumber);
        reservations.computeIfAbsent(date, k -> new HashMap<>()).put(seatNumber, token);

        // 좌석 예약 시 5분간 임시 배정되며, 시간 내 결제가 완료되지 않으면 자동으로 해제
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (reservations.get(date).get(seatNumber).equals(token)) {
                    reservations.get(date).remove(seatNumber);
                    availableSeats.get(date).add(seatNumber);
                    queueToken.setStatus("done");
                    processNextInQueue();
                }
            }
        }, 5 * 60 * 1000);

        Map<String, Object> response = new HashMap<>();
        response.put("reservationId", UUID.randomUUID().toString());
        return ResponseEntity.ok(response);
    }

    // 나머지 API (잔액 충전, 잔액 조회, 결제)는 동일하게 유지

    private boolean validateToken(String token) {
        AuthToken authToken = authTokens.get(token);
        return authToken != null && (System.currentTimeMillis() - authToken.getCreatedAt() < 30 * 60 * 1000);
    }

    private static class AuthToken {
        private final String uuid;
        private final long createdAt;

        public AuthToken(String uuid, long createdAt) {
            this.uuid = uuid;
            this.createdAt = createdAt;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    private static class QueueToken {
        private final String userId;
        private int seq;
        private String status;
        private final long createdAt;

        public QueueToken(String userId, int seq, String status, long createdAt) {
            this.userId = userId;
            this.seq = seq;
            this.status = status;
            this.createdAt = createdAt;
        }

        public int getSeq() {
            return seq;
        }

        public void setSeq(int seq) {
            this.seq = seq;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}