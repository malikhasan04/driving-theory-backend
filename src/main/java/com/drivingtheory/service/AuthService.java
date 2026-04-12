package com.drivingtheory.service;

import com.drivingtheory.dto.request.LoginRequest;
import com.drivingtheory.dto.request.RefreshTokenRequest;
import com.drivingtheory.dto.request.RegisterRequest;
import com.drivingtheory.dto.response.AuthResponse;
import com.drivingtheory.dto.response.UserResponse;
import com.drivingtheory.entity.RefreshToken;
import com.drivingtheory.entity.User;
import com.drivingtheory.enums.Role;
import com.drivingtheory.exception.AppExceptions;
import com.drivingtheory.repository.RefreshTokenRepository;
import com.drivingtheory.repository.UserRepository;
import com.drivingtheory.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository        userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder       passwordEncoder;
    private final JwtService            jwtService;
    private final AuthenticationManager authenticationManager;

    // milliseconds → convert to seconds when storing expiry
    @Value("${app.jwt.refresh-token-expiration}")
    private long refreshTokenExpirationMs;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail()))
            throw new AppExceptions.EmailAlreadyExistsException(req.getEmail());

        User user = User.builder()
                .fullName(req.getFullName())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .role(Role.USER)
                .build();
        userRepository.save(user);
        log.info("Registered new user: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));

        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new AppExceptions.ResourceNotFoundException("User not found"));

        // Revoke any existing refresh tokens
        refreshTokenRepository.deleteByUserId(user.getId());
        log.info("User logged in: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest req) {
        RefreshToken stored = refreshTokenRepository.findByToken(req.getRefreshToken())
                .orElseThrow(() -> new AppExceptions.InvalidTokenException("Refresh token not found"));

        if (stored.isExpired()) {
            refreshTokenRepository.delete(stored);
            throw new AppExceptions.InvalidTokenException("Refresh token expired, please login again");
        }

        User user = stored.getUser();
        refreshTokenRepository.delete(stored);
        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
        log.info("User {} logged out", userId);
    }

    public UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private AuthResponse buildAuthResponse(User user) {
        String accessToken  = jwtService.generateAccessToken(user);
        String rawRefresh   = jwtService.generateRefreshToken(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .token(rawRefresh)
                .user(user)
                // refreshTokenExpirationMs is in ms; convert to seconds for LocalDateTime offset
                .expiresAt(LocalDateTime.now().plusSeconds(refreshTokenExpirationMs / 1000))
                .build();
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.of(accessToken, rawRefresh, toUserResponse(user));
    }
}
