package com.drivingtheory.dto.response;

import lombok.*;

import java.io.Serializable;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StartTestResponse implements Serializable {
    private Long attemptId;
    private List<QuestionResponse> questions;
    private int totalQuestions;
    private int timeLimitSeconds;
}
