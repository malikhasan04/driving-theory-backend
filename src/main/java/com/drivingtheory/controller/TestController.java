package com.drivingtheory.controller;

import com.drivingtheory.dto.request.SubmitTestRequest;
import com.drivingtheory.dto.response.*;
import com.drivingtheory.entity.User;
import com.drivingtheory.service.TestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final TestService testService;

    /** Start a new test — returns attemptId + randomised question set (no correct options) */
    @PostMapping("/start")
    public ResponseEntity<ApiResponse<StartTestResponse>> startTest(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(testService.startTest(user)));
    }

    /** Submit completed answers — returns full scored result with review */
    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<TestResultResponse>> submitTest(
            @Valid @RequestBody SubmitTestRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(testService.submitTest(req, user)));
    }

    /** Retrieve result of a specific past attempt */
    @GetMapping("/attempt/{attemptId}")
    public ResponseEntity<ApiResponse<TestResultResponse>> getAttemptResult(
            @PathVariable Long attemptId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(testService.getAttemptResult(attemptId, user)));
    }

    /** All active questions for study/browse mode (served from Redis cache) */
    @GetMapping("/questions")
    public ResponseEntity<ApiResponse<List<QuestionResponse>>> getAllQuestions() {
        return ResponseEntity.ok(ApiResponse.success(testService.getAllActiveQuestions()));
    }
}
