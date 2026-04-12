package com.drivingtheory.dto.response;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

// Both outer and inner classes must implement Serializable for Redis caching
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserHistoryResponse implements Serializable {

    private long totalAttempts;
    private long passedAttempts;
    private long failedAttempts;
    private Double averageScore;
    private Integer bestScore;
    private List<AttemptSummary> recentAttempts;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttemptSummary implements Serializable {
        private Long attemptId;
        private int score;
        private int totalQuestions;
        private int correctAnswers;
        private boolean passed;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private Integer timeTakenSeconds;
    }
}
