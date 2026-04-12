package com.drivingtheory.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class SubmitTestRequest {

    @NotNull(message = "Attempt ID is required")
    private Long attemptId;

    // key = questionId, value = selected option letter (A/B/C/D)
    @NotEmpty(message = "Answers cannot be empty")
    private Map<Long, String> answers;

    @NotNull(message = "Time taken is required")
    private Integer timeTakenSeconds;
}
