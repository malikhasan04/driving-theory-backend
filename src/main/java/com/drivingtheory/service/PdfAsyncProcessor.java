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
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfAsyncProcessor {

    private final QuestionRepository  questionRepository;
    private final PdfUploadRepository pdfUploadRepository;
    private final CacheManager        cacheManager;

    // FORMAT 1 (TVDE_1.pdf): "1 - Question text" or "1- Question text"
    private static final Pattern Q_NUM_DASH = Pattern.compile(
            "^(\\d+)\\s*[-–]\\s*(.+)");

    // FORMAT 2 (trilingual): "English: Question text"
    private static final Pattern Q_ENGLISH = Pattern.compile(
            "^English:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    // Options: "a) text", "b) text", "A) text", "B) text"
    private static final Pattern OPTION_LOWER = Pattern.compile(
            "^([a-dA-D])\\)\\s*(.+)");

    // Options with checkmark: "A ✅ text"
    private static final Pattern OPTION_CHECK = Pattern.compile(
            "^([A-D])\\s*✅\\s*(.+)");

    // Correct answer line: "✅ Correct Answer... A)" or "Answer: A"
    private static final Pattern ANSWER_LINE = Pattern.compile(
            "(?:Correct\\s*Answer|Answer|Ans)[^A-Da-d]*([A-Da-d])[).]?",
            Pattern.CASE_INSENSITIVE);

    // CORRIGENDA table row: "28 A 78 C 128 A 178 C" (multiple pairs per line)
    private static final Pattern CORRIGENDA_PAIR = Pattern.compile(
            "(\\d+)\\s+([A-Da-d])");

    @Async("pdfTaskExecutor")
    public void processAsync(Long uploadId, byte[] pdfBytes) {
        PdfUpload upload = pdfUploadRepository.findById(uploadId)
                .orElseThrow(() -> new AppExceptions.ResourceNotFoundException(
                        "Upload not found: " + uploadId));
        try {
            List<Question> questions = extractQuestions(pdfBytes);

            if (questions.isEmpty()) {
                upload.setStatus(UploadStatus.FAILED);
                upload.setErrorMessage("No questions could be extracted. Please check the PDF format.");
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

    private List<Question> extractQuestions(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(doc);

            log.info("PDF text preview (first 400 chars): {}",
                    fullText.length() > 400 ? fullText.substring(0, 400) : fullText);

            // Detect format based on content
            boolean isPortugueseFormat = fullText.contains("CORRIGENDA") ||
                    Pattern.compile("^\\d+\\s*[-–]\\s*", Pattern.MULTILINE).matcher(fullText).find();
            boolean isTrilingualFormat = fullText.contains("English:") && fullText.contains("✅");

            List<Question> questions;
            if (isPortugueseFormat && !isTrilingualFormat) {
                log.info("Detected Portuguese numbered format (with CORRIGENDA)");
                questions = parsePortugueseFormat(fullText);
            } else if (isTrilingualFormat) {
                log.info("Detected trilingual format (Portuguese/English/Urdu with checkmarks)");
                questions = parseTrilingualFormat(fullText);
            } else {
                log.info("Detected simple numbered format");
                questions = parseSimpleFormat(fullText);
            }

            log.info("Parsed {} questions total", questions.size());
            return questions;
        }
    }

    // ── FORMAT 1: Portuguese numbered with CORRIGENDA answer key ──────────────

    private List<Question> parsePortugueseFormat(String text) {
        String[] lines = text.split("\\r?\\n");

        // Step 1: Extract CORRIGENDA answer key  (question# -> correct letter)
        Map<Integer, String> answerKey = extractCorrigenda(lines);
        log.info("Extracted {} answers from CORRIGENDA", answerKey.size());

        // Step 2: Parse questions and options
        List<QuestionBlock> blocks = new ArrayList<>();
        QuestionBlock current = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // Stop parsing questions when we hit the answer key section
            if (line.contains("CORRIGENDA") || line.contains("NR") && line.contains("Pergunta") && line.contains("Resposta")) {
                break;
            }

            // Match question: "1 - Question text" or "1- Question text"
            Matcher qm = Q_NUM_DASH.matcher(line);
            if (qm.matches()) {
                if (current != null && current.hasMinimumData()) {
                    blocks.add(current);
                }
                current = new QuestionBlock();
                current.questionNum = Integer.parseInt(qm.group(1));
                current.questionText = qm.group(2).trim();
                continue;
            }

            if (current == null) continue;

            // Accumulate multi-line question text (before first option)
            // Match options: "a) text", "b) text"
            Matcher om = OPTION_LOWER.matcher(line);
            if (om.matches()) {
                String letter = om.group(1).toUpperCase();
                String value  = om.group(2).trim();
                // Skip header-like lines
                if (!value.isBlank()) {
                    setOption(current, letter, value);
                }
                continue;
            }

            // Multi-line question text (before any option is set)
            if (current.optionA == null && !line.matches("^Pág\\..*") && !line.contains("CMTVDE")) {
                current.questionText = current.questionText + " " + line;
            }

            // Multi-line option text
            if (current.optionA != null && !line.matches("^[a-dA-D]\\).*") && !line.matches("^\\d+\\s*[-–].*")) {
                // append to last set option
                if (current.optionD != null) current.optionD += " " + line;
                else if (current.optionC != null) current.optionC += " " + line;
                else if (current.optionB != null) current.optionB += " " + line;
                else if (current.optionA != null) current.optionA += " " + line;
            }
        }

        // Flush last block
        if (current != null && current.hasMinimumData()) {
            blocks.add(current);
        }

        // Step 3: Build questions merging with answer key
        List<Question> questions = new ArrayList<>();
        for (QuestionBlock b : blocks) {
            fillMissingOptions(b);
            // Get answer from CORRIGENDA
            if (b.questionNum != null && answerKey.containsKey(b.questionNum)) {
                b.correctOption = answerKey.get(b.questionNum).toUpperCase();
            } else {
                b.correctOption = "A"; // fallback
            }
            questions.add(buildQuestion(b));
        }
        return questions;
    }

    /**
     * Parse CORRIGENDA section which looks like:
     * "1 C 51 B 101 C 151 A 201 A"
     * "2 C 52 C 102 A 152 D 202 A"
     */
    private Map<Integer, String> extractCorrigenda(String[] lines) {
        Map<Integer, String> map = new HashMap<>();
        boolean inCorrigenda = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.contains("CORRIGENDA")) {
                inCorrigenda = true;
                continue;
            }

            if (!inCorrigenda) continue;

            // Each line can have multiple "number letter" pairs
            Matcher m = CORRIGENDA_PAIR.matcher(line);
            while (m.find()) {
                int num = Integer.parseInt(m.group(1));
                String letter = m.group(2);
                // Only store if looks like a valid question number (1-300)
                if (num >= 1 && num <= 300) {
                    map.put(num, letter);
                }
            }
        }
        return map;
    }

    // ── FORMAT 2: Trilingual Portuguese/English/Urdu with ✅ ──────────────────

    private List<Question> parseTrilingualFormat(String text) {
        List<QuestionBlock> blocks = new ArrayList<>();
        String[]            lines  = text.split("\\r?\\n");
        QuestionBlock       current = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.contains("Correct Answer") || line.contains("جواب صحیح")) {
                if (current != null) {
                    Matcher am = ANSWER_LINE.matcher(line);
                    if (am.find()) current.correctOption = am.group(1).toUpperCase();
                }
                continue;
            }

            Matcher am = ANSWER_LINE.matcher(line);
            if (am.find() && !line.toLowerCase().startsWith("option") && current != null) {
                current.correctOption = am.group(1).toUpperCase();
                continue;
            }

            Matcher ocm = OPTION_CHECK.matcher(line);
            if (ocm.matches() && current != null) {
                String letter = ocm.group(1).toUpperCase();
                String value  = extractEnglishPart(ocm.group(2));
                setOption(current, letter, value);
                current.correctOption = letter;
                continue;
            }

            Matcher om = OPTION_LOWER.matcher(line);
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

            Matcher em = Q_ENGLISH.matcher(line);
            if (em.matches()) {
                if (current != null && current.hasMinimumData()) {
                    fillMissingOptions(current);
                    blocks.add(current);
                }
                current = new QuestionBlock();
                current.questionText = em.group(1).trim();
                continue;
            }

            if (current != null && current.optionA == null
                    && !line.toLowerCase().startsWith("portuguese:")
                    && !line.toLowerCase().startsWith("option")
                    && !containsOnlyUrdu(line)) {
                current.questionText = (current.questionText == null ? "" :
                        current.questionText + " ") + line;
            }
        }

        if (current != null && current.hasMinimumData()) {
            fillMissingOptions(current);
            blocks.add(current);
        }

        List<Question> questions = new ArrayList<>();
        for (QuestionBlock b : blocks) {
            if (b.correctOption == null) b.correctOption = "A";
            questions.add(buildQuestion(b));
        }
        return questions;
    }

    // ── FORMAT 3: Simple numbered "1. Question / A) Option / Answer: A" ───────

    private List<Question> parseSimpleFormat(String text) {
        List<QuestionBlock> blocks = new ArrayList<>();
        String[]            lines  = text.split("\\r?\\n");
        QuestionBlock       current = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.matches("^\\d+[.):] .+")) {
                if (current != null && current.hasMinimumData()) {
                    fillMissingOptions(current);
                    blocks.add(current);
                }
                current = new QuestionBlock();
                current.questionText = line.replaceFirst("^\\d+[.):] ", "").trim();
                continue;
            }

            if (current == null) continue;

            Matcher am = ANSWER_LINE.matcher(line);
            if (am.find()) { current.correctOption = am.group(1).toUpperCase(); continue; }

            Matcher om = OPTION_LOWER.matcher(line);
            if (om.matches()) {
                setOption(current, om.group(1).toUpperCase(), om.group(2).trim());
                continue;
            }

            if (line.toLowerCase().startsWith("explanation:")) {
                current.explanation = line.substring("explanation:".length()).trim();
            }
        }

        if (current != null && current.hasMinimumData()) {
            fillMissingOptions(current);
            blocks.add(current);
        }

        List<Question> questions = new ArrayList<>();
        for (QuestionBlock b : blocks) {
            if (b.correctOption == null) b.correctOption = "A";
            questions.add(buildQuestion(b));
        }
        return questions;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractEnglishPart(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.ARABIC
                    || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B) {
                break;
            }
            sb.append(c);
        }
        return sb.toString().replaceAll("✅", "").replaceAll("\\s+", " ").trim();
    }

    private boolean containsOnlyUrdu(String line) {
        if (line == null || line.isBlank()) return false;
        long arabicChars = line.chars().filter(c -> {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
            return b == Character.UnicodeBlock.ARABIC
                    || b == Character.UnicodeBlock.ARABIC_SUPPLEMENT
                    || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                    || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B;
        }).count();
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

    private void fillMissingOptions(QuestionBlock b) {
        if (b.optionA == null) b.optionA = "N/A";
        if (b.optionB == null) b.optionB = "N/A";
        if (b.optionC == null) b.optionC = "N/A";
        if (b.optionD == null) b.optionD = "N/A";
    }

    private Question buildQuestion(QuestionBlock b) {
        return Question.builder()
                .questionText(b.questionText != null ? b.questionText.trim() : "")
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
        if (cache != null) cache.clear();
    }

    private static class QuestionBlock {
        Integer questionNum;
        String  questionText;
        String  optionA, optionB, optionC, optionD;
        String  correctOption;
        String  explanation;

        boolean hasMinimumData() {
            return questionText != null && !questionText.isBlank()
                    && optionA != null && optionB != null;
        }
    }
}
