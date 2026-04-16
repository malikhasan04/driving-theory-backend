package com.drivingtheory.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserHistoryResponse implements Serializable {

    private long   totalAttempts;
    private long   passedAttempts;
    private long   failedAttempts;
    private Double  averageScore;
    private Integer bestScore;
    private List<AttemptSummary> recentAttempts;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttemptSummary implements Serializable {
        private Long    attemptId;
        private int     score;
        private int     totalQuestions;
        private int     correctAnswers;
        private boolean passed;

        @JsonSerialize(using = LocalDateTimeSerializer.class)
        @JsonDeserialize(using = LocalDateTimeDeserializer.class)
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime startedAt;

        @JsonSerialize(using = LocalDateTimeSerializer.class)
        @JsonDeserialize(using = LocalDateTimeDeserializer.class)
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime completedAt;

        private Integer timeTakenSeconds;
    }
}