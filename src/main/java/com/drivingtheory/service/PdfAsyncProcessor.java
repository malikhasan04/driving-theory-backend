package com.drivingtheory.service;

import com.drivingtheory.config.RedisConfig;
import com.drivingtheory.entity.PdfUpload;
import com.drivingtheory.entity.Question;
import com.drivingtheory.enums.UploadStatus;
import com.drivingtheory.exception.AppExceptions;
import com.drivingtheory.repository.PdfUploadRepository;
import com.drivingtheory.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Separate Spring bean so that calling processAsync() from PdfExtractionService
 * goes through the Spring AOP proxy, which is required for @Async to work.
 * If processAsync() were in PdfExtractionService and called from within the same
 * bean, Spring's proxy would be bypassed and the method would block the HTTP thread.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PdfAsyncProcessor {

    private final QuestionRepository    questionRepository;
    private final PdfUploadRepository   pdfUploadRepository;
    private final CloudinaryService     cloudinaryService;
    private final CacheManager          cacheManager;

    private static final Pattern QUESTION_START = Pattern.compile(
            "^(?:Q(?:uestion)?\\s*)?\\d+[.):]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION_PATTERN = Pattern.compile(
            "^\\(?([A-Da-d])[.):]\\s*(.+)");
    private static final Pattern ANSWER_PATTERN = Pattern.compile(
            "(?:Answer|Correct(?:\\s+Answer)?|Ans)[:\\s]+([A-Da-d])",
            Pattern.CASE_INSENSITIVE);

    @Async("pdfTaskExecutor")
    public void processAsync(Long uploadId, byte[] pdfBytes) {
        PdfUpload upload = pdfUploadRepository.findById(uploadId)
                .orElseThrow(() -> new AppExceptions.ResourceNotFoundException(
                        "Upload not found: " + uploadId));
        try {
            List<Question> questions = extractQuestions(pdfBytes);
            questionRepository.saveAll(questions);

            upload.setQuestionsExtracted(questions.size());
            upload.setStatus(UploadStatus.COMPLETED);
            upload.setCompletedAt(LocalDateTime.now());
            pdfUploadRepository.save(upload);

            evictQuestionBankCache();
            log.info("PDF processing complete: {} questions extracted (upload {})",
                    questions.size(), uploadId);

        } catch (Exception e) {
            log.error("PDF processing failed (upload {}): {}", uploadId, e.getMessage(), e);
            upload.setStatus(UploadStatus.FAILED);
            upload.setErrorMessage(e.getMessage());
            upload.setCompletedAt(LocalDateTime.now());
            pdfUploadRepository.save(upload);
        }
    }

    // ── Extraction ───────────────────────────────────────────────────────────

    private List<Question> extractQuestions(byte[] pdfBytes) throws IOException {
        List<Question> questions = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(doc);

            List<BufferedImage> images = extractPageImages(doc);
            List<QuestionBlock> blocks = parseBlocks(fullText);

            for (int i = 0; i < blocks.size(); i++) {
                Question q = buildQuestion(blocks.get(i));
                if (i < images.size()) {
                    try {
                        CloudinaryService.UploadResult r =
                                cloudinaryService.uploadImage(images.get(i), "q" + (i + 1));
                        q.setImageUrl(r.secureUrl());
                        q.setImagePublicId(r.publicId());
                    } catch (IOException e) {
                        log.warn("Could not upload image for question {}: {}", i + 1, e.getMessage());
                    }
                }
                questions.add(q);
            }
        }
        return questions;
    }

    private List<BufferedImage> extractPageImages(PDDocument doc) {
        List<BufferedImage> images = new ArrayList<>();
        for (PDPage page : doc.getPages()) {
            try {
                var resources = page.getResources();
                if (resources == null) continue;
                for (var name : resources.getXObjectNames()) {
                    var xObj = resources.getXObject(name);
                    if (xObj instanceof PDImageXObject imgXObj) {
                        images.add(imgXObj.getImage());
                    }
                }
            } catch (IOException e) {
                log.warn("Could not extract image from page: {}", e.getMessage());
            }
        }
        return images;
    }

    /**
     * Expected PDF format:
     *   1. Question text here
     *   A) Option A
     *   B) Option B
     *   C) Option C
     *   D) Option D
     *   Answer: A
     *   Explanation: Optional explanation text
     */
    private List<QuestionBlock> parseBlocks(String text) {
        List<QuestionBlock> blocks   = new ArrayList<>();
        String[]            lines    = text.split("\\r?\\n");
        QuestionBlock       current  = null;
        StringBuilder       qText    = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher qm = QUESTION_START.matcher(line);
            if (qm.matches()) {
                if (current != null && current.isValid()) {
                    if (qText.length() > 0) current.questionText = qText.toString().trim();
                    blocks.add(current);
                }
                current = new QuestionBlock();
                current.questionText = qm.group(1).trim();
                qText = new StringBuilder();
                continue;
            }

            if (current == null) continue;

            Matcher om = OPTION_PATTERN.matcher(line);
            if (om.matches()) {
                String letter = om.group(1).toUpperCase();
                String value  = om.group(2).trim();
                switch (letter) {
                    case "A" -> current.optionA = value;
                    case "B" -> current.optionB = value;
                    case "C" -> current.optionC = value;
                    case "D" -> current.optionD = value;
                }
                continue;
            }

            Matcher am = ANSWER_PATTERN.matcher(line);
            if (am.find()) {
                current.correctOption = am.group(1).toUpperCase();
                continue;
            }

            if (line.toLowerCase().startsWith("explanation:")) {
                current.explanation = line.substring("explanation:".length()).trim();
                continue;
            }

            // Multi-line question text (before any option is set)
            if (current.optionA == null) {
                qText.append(" ").append(line);
            }
        }

        // Flush last block
        if (current != null && current.isValid()) {
            if (qText.length() > 0 && current.questionText != null)
                current.questionText = (current.questionText + " " + qText).trim();
            blocks.add(current);
        }
        return blocks;
    }

    private Question buildQuestion(QuestionBlock b) {
        return Question.builder()
                .questionText(b.questionText)
                .optionA(b.optionA)
                .optionB(b.optionB)
                .optionC(b.optionC)
                .optionD(b.optionD)
                .correctOption(b.correctOption)
                .explanation(b.explanation)
                .active(true)
                .build();
    }

    private void evictQuestionBankCache() {
        var cache = cacheManager.getCache(RedisConfig.CACHE_QUESTION_BANK);
        if (cache != null) {
            cache.clear();
            log.info("Evicted question-bank cache after PDF ingestion");
        }
    }

    // ── Inner parsing state ──────────────────────────────────────────────────

    private static class QuestionBlock {
        String questionText;
        String optionA, optionB, optionC, optionD;
        String correctOption;
        String explanation;

        boolean isValid() {
            return questionText != null && !questionText.isBlank()
                    && optionA != null && optionB != null
                    && optionC != null && optionD != null
                    && correctOption != null;
        }
    }
}
