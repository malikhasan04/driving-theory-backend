package com.drivingtheory.dto.response;

import lombok.*;

import java.io.Serializable;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuthResponse implements Serializable {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private UserResponse user;

    public static AuthResponse of(String accessToken, String refreshToken, UserResponse user) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .user(user)
                .build();
    }
}
