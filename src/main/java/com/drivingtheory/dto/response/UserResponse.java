package com.drivingtheory.dto.response;

import com.drivingtheory.enums.Role;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserResponse implements Serializable {
    private Long id;
    private String email;
    private String fullName;
    private Role role;
    private LocalDateTime createdAt;
}
