package com.drivingtheory.controller;

import com.drivingtheory.dto.request.LoginRequest;
import com.drivingtheory.dto.request.RefreshTokenRequest;
import com.drivingtheory.dto.request.RegisterRequest;
import com.drivingtheory.dto.response.ApiResponse;
import com.drivingtheory.dto.response.AuthResponse;
import com.drivingtheory.entity.User;
import com.drivingtheory.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful", authService.register(req)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(ApiResponse.success("Login successful", authService.login(req)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest req) {
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", authService.refreshToken(req)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal User user) {
        authService.logout(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully", null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Object>> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(authService.toUserResponse(user)));
    }

    @GetMapping("/debug")
    public String debug() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        String freshHash = encoder.encode("Admin@123");
        String dbHash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
        boolean matches = encoder.matches("Admin@123", dbHash);
        return "Fresh hash: " + freshHash + " | Matches: " + matches;
    }
}
