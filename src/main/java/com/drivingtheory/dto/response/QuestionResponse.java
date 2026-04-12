package com.drivingtheory.dto.response;

import com.drivingtheory.enums.Difficulty;
import lombok.*;

import java.io.Serializable;

// Serializable is required — this class is cached in Redis via @Cacheable
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionResponse implements Serializable {
    private Long id;
    private String questionText;
    private String optionA;
    private String optionB;
    private String optionC;
    private String optionD;
    private String imageUrl;
    private String category;
    private Difficulty difficulty;
    // correctOption intentionally omitted — revealed only in result responses
}
