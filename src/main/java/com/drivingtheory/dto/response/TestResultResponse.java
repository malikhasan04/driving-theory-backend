package com.drivingtheory.dto.response;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestResultResponse implements Serializable {

    private Long attemptId;
    private int score;
    private int totalQuestions;
    private int correctAnswers;
    private boolean passed;
    private int passPercentage;
    private Integer timeTakenSeconds;
    private LocalDateTime completedAt;
    private List<AnswerReviewResponse> answers;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AnswerReviewResponse implements Serializable {
        private Long questionId;
        private String questionText;
        private String imageUrl;
        private String optionA;
        private String optionB;
        private String optionC;
        private String optionD;
        private String selectedOption;
        private String correctOption;
        private String explanation;
        private boolean correct;
    }
}
