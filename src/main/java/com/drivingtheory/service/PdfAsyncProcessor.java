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

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfAsyncProcessor {

    private final QuestionRepository    questionRepository;
    private final PdfUploadRepository   pdfUploadRepository;
    private final CloudinaryService     cloudinaryService;
    private final CacheManager          cacheManager;

    // Matches: "English: Some question text here"
    private static final Pattern ENGLISH_LABEL = Pattern.compile(
            "^English:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    // Matches option lines like: "A Alert" or "A) Alert" or "A. Alert"
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^([A-D])[.)\\s]\\s*(.+)");

    // Matches correct answer markers:
    // "✅ Correct Answer / جواب صحیح: A)" or "Correct Answer: A" or "Answer: A"
    private static final Pattern ANSWER_MARKER = Pattern.compile(
            "(?:Correct\\s*Answer|Answer|Ans)[^A-D]*([A-D])[).]?",
            Pattern.CASE_INSENSITIVE);

    // Matches option with checkmark: "A ✅ Alert" or "A ✅ Alerta Alert"
    private static final Pattern OPTION_WITH_CHECK = Pattern.compile(
            "^([A-D])\\s*✅\\s*(.+)");

    // Matches question number start: "1." or "1)" or "Portuguese: text"
    private static final Pattern QUESTION_NUMBER = Pattern.compile(
            "^(Portuguese:|\\d+[.):]\\s*.+)", Pattern.CASE_INSENSITIVE);

    @Async("pdfTaskExecutor")
    public void processAsync(Long uploadId, byte[] pdfBytes) {
        PdfUpload upload = pdfUploadRepository.findById(uploadId)
                .orElseThrow(() -> new AppExceptions.ResourceNotFoundException(
                        "Upload not found: " + uploadId));
        try {
            List<Question> questions = extractQuestions(pdfBytes);

            if (questions.isEmpty()) {
                upload.setStatus(UploadStatus.FAILED);
                upload.setErrorMessage(
                        "No questions could be extracted. Please check the PDF format. " +
                                "The PDF should contain questions with English text, options A/B/C/D, and correct answers marked with ✅ or 'Answer: X'.");
                upload.setCompletedAt(LocalDateTime.now());
                pdfUploadRepository.save(upload);
                return;
            }

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

            log.info("PDF text preview: {}", fullText.length() > 500 ? fullText.substring(0, 500) : fullText);

            List<BufferedImage> images = extractPageImages(doc);
            List<QuestionBlock> blocks = parseBlocks(fullText);

            log.info("Parsed {} question blocks from PDF", blocks.size());

            for (int i = 0; i < blocks.size(); i++) {
                Question q = buildQuestion(blocks.get(i));
                // Try to attach image if available
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
                        BufferedImage img = imgXObj.getImage();
                        // Skip very small images (icons, logos)
                        if (img.getWidth() > 50 && img.getHeight() > 50) {
                            images.add(img);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Could not extract image from page: {}", e.getMessage());
            }
        }
        return images;
    }

    /**
     * Supports two PDF formats:
     *
     * FORMAT 1 (trilingual with ✅):
     *   Portuguese: Question in Portuguese
     *   English: Question in English
     *   Urdu: Question in Urdu
     *   Option  Portuguese  English  Urdu
     *   A ✅    Alerta      Alert    ارلٹ
     *   B       Reconhecimento  Recognition  شناخت
     *   ✅ Correct Answer / جواب صحیح: A)
     *
     * FORMAT 2 (simple):
     *   1. What does a red traffic light mean?
     *   A) Stop completely
     *   B) Slow down
     *   C) Proceed with caution
     *   D) Turn left only
     *   Answer: A
     *   Explanation: optional
     */
    private List<QuestionBlock> parseBlocks(String text) {
        List<QuestionBlock> blocks  = new ArrayList<>();
        String[]            lines   = text.split("\\r?\\n");
        QuestionBlock       current = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // ── Detect correct answer from ✅ Correct Answer line ──
            if (line.contains("Correct Answer") || line.contains("جواب صحیح")) {
                if (current != null) {
                    Matcher am = ANSWER_MARKER.matcher(line);
                    if (am.find()) {
                        current.correctOption = am.group(1).toUpperCase();
                    }
                }
                continue;
            }

            // ── Detect simple "Answer: A" line ──
            Matcher am = ANSWER_MARKER.matcher(line);
            if (am.find() && !line.toLowerCase().startsWith("option") && current != null) {
                current.correctOption = am.group(1).toUpperCase();
                continue;
            }

            // ── Detect option line with checkmark: "A ✅ Text" ──
            Matcher ocm = OPTION_WITH_CHECK.matcher(line);
            if (ocm.matches() && current != null) {
                String letter = ocm.group(1).toUpperCase();
                // Extract English text — take text after ✅, ignore Urdu/Arabic
                String value = extractEnglishPart(ocm.group(2));
                setOption(current, letter, value);
                // This option is correct
                current.correctOption = letter;
                continue;
            }

            // ── Detect regular option line: "A) Text" or "A Text" ──
            Matcher om = OPTION_LINE.matcher(line);
            if (om.matches() && current != null) {
                String letter = om.group(1).toUpperCase();
                String value  = extractEnglishPart(om.group(2));
                if (!value.isBlank() && !value.equalsIgnoreCase("Portuguese")
                        && !value.equalsIgnoreCase("English")
                        && !value.equalsIgnoreCase("Option")) {
                    setOption(current, letter, value);
                }
                continue;
            }

            // ── Detect English question line: "English: Question text" ──
            Matcher em = ENGLISH_LABEL.matcher(line);
            if (em.matches()) {
                // Save previous block
                if (current != null && current.hasMinimumData()) {
                    fillMissingOptions(current);
                    blocks.add(current);
                }
                current = new QuestionBlock();
                current.questionText = em.group(1).trim();
                continue;
            }

            // ── Detect numbered question: "1. Question text" ──
            if (line.matches("^\\d+[.):] .+")) {
                if (current != null && current.hasMinimumData()) {
                    fillMissingOptions(current);
                    blocks.add(current);
                }
                current = new QuestionBlock();
                // Remove the number prefix
                current.questionText = line.replaceFirst("^\\d+[.):] ", "").trim();
                continue;
            }

            // ── Explanation ──
            if (line.toLowerCase().startsWith("explanation:") && current != null) {
                current.explanation = line.substring("explanation:".length()).trim();
                continue;
            }

            // ── Multi-line question text accumulation ──
            if (current != null && current.optionA == null
                    && !line.toLowerCase().startsWith("portuguese:")
                    && !line.toLowerCase().startsWith("option")
                    && !containsOnlyUrdu(line)) {
                current.questionText = (current.questionText == null ? "" : current.questionText + " ") + line;
            }
        }

        // Flush last block
        if (current != null && current.hasMinimumData()) {
            fillMissingOptions(current);
            blocks.add(current);
        }

        return blocks;
    }

    /**
     * Extract the English portion from a mixed-language string.
     * The string typically has English text followed by Urdu/Arabic script.
     * We keep text up to the first Arabic/Urdu character.
     */
    private String extractEnglishPart(String text) {
        if (text == null) return "";
        // Find first Arabic/Urdu character (Unicode range 0600-06FF, 0750-077F, FB50-FDFF, FE70-FEFF)
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC_SUPPLEMENT
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B) {
                break;
            }
            sb.append(c);
        }
        return sb.toString().trim()
                .replaceAll("✅", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Check if a line contains mostly Urdu/Arabic characters.
     */
    private boolean containsOnlyUrdu(String line) {
        if (line == null || line.isBlank()) return false;
        long arabicChars = line.chars()
                .filter(c -> {
                    Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                    return block == Character.UnicodeBlock.ARABIC
                            || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT
                            || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                            || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B;
                })
                .count();
        return arabicChars > line.length() * 0.3;
    }

    private void setOption(QuestionBlock block, String letter, String value) {
        if (value == null || value.isBlank()) return;
        switch (letter) {
            case "A" -> block.optionA = value;
            case "B" -> block.optionB = value;
            case "C" -> block.optionC = value;
            case "D" -> block.optionD = value;
        }
    }

    /**
     * Fill missing options with placeholder text so questions with only 2 or 3
     * options can still be saved (DB columns are NOT NULL).
     */
    private void fillMissingOptions(QuestionBlock b) {
        if (b.optionA == null) b.optionA = "N/A";
        if (b.optionB == null) b.optionB = "N/A";
        if (b.optionC == null) b.optionC = "N/A";
        if (b.optionD == null) b.optionD = "N/A";
        // Default correct answer if not found
        if (b.correctOption == null) b.correctOption = "A";
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

        /** Requires at least a question and 2 options. */
        boolean hasMinimumData() {
            return questionText != null && !questionText.isBlank()
                    && optionA != null && optionB != null;
        }
    }
}
