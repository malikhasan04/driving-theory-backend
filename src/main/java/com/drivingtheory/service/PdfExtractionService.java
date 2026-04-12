package com.drivingtheory.service;

import com.drivingtheory.entity.PdfUpload;
import com.drivingtheory.entity.User;
import com.drivingtheory.enums.UploadStatus;
import com.drivingtheory.exception.AppExceptions;
import com.drivingtheory.repository.PdfUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfExtractionService {

    private final PdfUploadRepository pdfUploadRepository;
    // Injected as a separate bean so the @Async proxy on processAsync() is invoked.
    // Calling an @Async method on 'this' would bypass Spring's proxy.
    private final PdfAsyncProcessor pdfAsyncProcessor;

    @Transactional
    public PdfUpload initiateUpload(MultipartFile file, User uploadedBy) {
        if (file.isEmpty())
            throw new AppExceptions.PdfExtractionException("Uploaded file is empty");

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf"))
            throw new AppExceptions.PdfExtractionException("Only PDF files are accepted");

        PdfUpload upload = PdfUpload.builder()
                .originalFilename(filename)
                .uploadedBy(uploadedBy)
                .status(UploadStatus.PROCESSING)
                .build();
        PdfUpload saved = pdfUploadRepository.save(upload);

        try {
            byte[] bytes = file.getBytes();
            // Delegates to a different bean → Spring AOP proxy is invoked → @Async fires
            pdfAsyncProcessor.processAsync(saved.getId(), bytes);
        } catch (IOException e) {
            saved.setStatus(UploadStatus.FAILED);
            saved.setErrorMessage("Failed to read file bytes: " + e.getMessage());
            pdfUploadRepository.save(saved);
        }

        return saved;
    }
}
