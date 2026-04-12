package com.drivingtheory.controller;

import com.drivingtheory.dto.response.ApiResponse;
import com.drivingtheory.dto.response.UserHistoryResponse;
import com.drivingtheory.entity.User;
import com.drivingtheory.service.TestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final TestService testService;

    /**
     * Returns the authenticated user's full test history:
     * total/pass/fail counts, avg score, best score, paginated attempts.
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<UserHistoryResponse>> getHistory(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success(testService.getUserHistory(user, page, size)));
    }
}
