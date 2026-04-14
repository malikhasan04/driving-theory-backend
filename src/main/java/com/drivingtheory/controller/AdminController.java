package com.drivingtheory.controller;

import com.drivingtheory.dto.response.ApiResponse;
import com.drivingtheory.dto.response.PageResponse;
import com.drivingtheory.dto.response.QuestionResponse;
import com.drivingtheory.entity.PdfUpload;
import com.drivingtheory.entity.Question;
import com.drivingtheory.entity.User;
import com.drivingtheory.enums.UploadStatus;
import com.drivingtheory.exception.AppExceptions;
import com.drivingtheory.repository.PdfUploadRepository;
import com.drivingtheory.repository.QuestionRepository;
import com.drivingtheory.service.PdfExtractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final PdfExtractionService  pdfExtractionService;
    private final PdfUploadRepository   pdfUploadRepository;
    private final QuestionRepository    questionRepository;

    @PostMapping("/upload-pdf")
    public ResponseEntity<ApiResponse<PdfUploadStatusResponse>> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User admin) {
        PdfUpload upload = pdfExtractionService.initiateUpload(file, admin);
        // Return a simple DTO — never return PdfUpload entity directly (Hibernate proxy issue)
        return ResponseEntity.accepted().body(ApiResponse.success(
                "PDF received. Extraction running in background.",
                toStatusResponse(upload)));
    }

    @GetMapping("/upload-status/{uploadId}")
    public ResponseEntity<ApiResponse<PdfUploadStatusResponse>> getUploadStatus(
            @PathVariable Long uploadId) {
        PdfUpload upload = pdfUploadRepository.findById(uploadId)
                .orElseThrow(() -> new AppExceptions.ResourceNotFoundException(
                        "Upload not found: " + uploadId));
        return ResponseEntity.ok(ApiResponse.success(toStatusResponse(upload)));
    }

    @GetMapping("/uploads")
    public ResponseEntity<ApiResponse<PageResponse<PdfUploadStatusResponse>>> listUploads(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<PdfUploadStatusResponse> uploads = pdfUploadRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .map(this::toStatusResponse);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(uploads)));
    }

    @GetMapping("/questions")
    public ResponseEntity<ApiResponse<PageResponse<QuestionResponse>>> listQuestions(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<QuestionResponse> mapped = questionRepository
                .findByActiveTrue(PageRequest.of(page, size))
                .map(q -> QuestionResponse.builder()
                        .id(q.getId()).questionText(q.getQuestionText())
                        .optionA(q.getOptionA()).optionB(q.getOptionB())
                        .optionC(q.getOptionC()).optionD(q.getOptionD())
                        .imageUrl(q.getImageUrl()).category(q.getCategory())
                        .difficulty(q.getDifficulty()).build());
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(mapped)));
    }

    @DeleteMapping("/questions/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivateQuestion(@PathVariable Long id) {
        Question q = questionRepository.findById(id)
                .orElseThrow(() -> new AppExceptions.ResourceNotFoundException(
                        "Question not found: " + id));
        q.setActive(false);
        questionRepository.save(q);
        return ResponseEntity.ok(ApiResponse.success("Question deactivated", null));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<AdminStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(new AdminStatsResponse(
                questionRepository.count(),
                questionRepository.countByActiveTrue())));
    }

    // Convert entity to DTO — avoids Hibernate proxy serialization error
    private PdfUploadStatusResponse toStatusResponse(PdfUpload u) {
        return new PdfUploadStatusResponse(
                u.getId(),
                u.getOriginalFilename(),
                u.getStatus().name(),
                u.getQuestionsExtracted(),
                u.getErrorMessage(),
                u.getCreatedAt(),
                u.getCompletedAt()
        );
    }

    public record PdfUploadStatusResponse(
            Long uploadId,
            String filename,
            String status,
            int questionsExtracted,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {}

    public record AdminStatsResponse(long totalQuestions, long activeQuestions) {}
}
