package com.drivingtheory.service;

import com.drivingtheory.config.RedisConfig;
import com.drivingtheory.dto.request.SubmitTestRequest;
import com.drivingtheory.dto.response.*;
import com.drivingtheory.entity.AttemptAnswer;
import com.drivingtheory.entity.Question;
import com.drivingtheory.entity.TestAttempt;
import com.drivingtheory.entity.User;
import com.drivingtheory.exception.AppExceptions;
import com.drivingtheory.repository.QuestionRepository;
import com.drivingtheory.repository.TestAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestService {

    private final QuestionRepository    questionRepository;
    private final TestAttemptRepository testAttemptRepository;

    @Value("${app.test.pass-percentage:80}")
    private int passPercentage;

    @Value("${app.test.questions-per-test:20}")
    private int questionsPerTest;

    // ── Questions ────────────────────────────────────────────────────────────

    @Cacheable(value = RedisConfig.CACHE_QUESTION_BANK, key = "'all-active'")
    @Transactional(readOnly = true)
    public List<QuestionResponse> getAllActiveQuestions() {
        return questionRepository.findByActiveTrue()
                .stream().map(this::toQuestionResponse).collect(Collectors.toList());
    }

    // ── Test lifecycle ───────────────────────────────────────────────────────

    @Transactional
    public StartTestResponse startTest(User user) {
        List<Question> questions = questionRepository.findRandomActiveQuestions(questionsPerTest);
        if (questions.isEmpty())
            throw new AppExceptions.InvalidTestStateException(
                    "No questions available. Please contact the administrator.");

        TestAttempt attempt = TestAttempt.builder()
                .user(user)
                .totalQuestions(questions.size())
                .score(0)
                .correctAnswers(0)
                .passed(false)
                .build();
        testAttemptRepository.save(attempt);
        log.info("Started attempt {} for user {}", attempt.getId(), user.getEmail());

        return StartTestResponse.builder()
                .attemptId(attempt.getId())
                .questions(questions.stream().map(this::toQuestionResponse).collect(Collectors.toList()))
                .totalQuestions(questions.size())
                .timeLimitSeconds(questions.size() * 60)
                .build();
    }

    @Transactional
    // allEntries=true evicts all pages for all users — needed because the cache key
    // includes page+size, so a per-user key eviction would miss other pages
    @CacheEvict(value = RedisConfig.CACHE_USER_HISTORY, allEntries = true)
    public TestResultResponse submitTest(SubmitTestRequest req, User user) {
        TestAttempt attempt = testAttemptRepository.findById(req.getAttemptId())
                .orElseThrow(() -> new AppExceptions.ResourceNotFoundException(
                        "Test attempt not found: " + req.getAttemptId()));

        if (!attempt.getUser().getId().equals(user.getId()))
            throw new AppExceptions.InvalidTestStateException("This attempt does not belong to you");

        if (attempt.getCompletedAt() != null)
            throw new AppExceptions.InvalidTestStateException("This attempt has already been submitted");

        Map<Long, String> rawAnswers = req.getAnswers();
        Map<Long, Question> questionMap = questionRepository
                .findAllById(new ArrayList<>(rawAnswers.keySet()))
                .stream().collect(Collectors.toMap(Question::getId, Function.identity()));

        int correct = 0;
        List<AttemptAnswer> attemptAnswers = new ArrayList<>();
        List<TestResultResponse.AnswerReviewResponse> reviews = new ArrayList<>();

        for (Map.Entry<Long, String> entry : rawAnswers.entrySet()) {
            Long    qId      = entry.getKey();
            String  selected = entry.getValue() == null ? null : entry.getValue().toUpperCase();
            Question q       = questionMap.get(qId);
            if (q == null) continue;

            boolean isCorrect = q.getCorrectOption().equalsIgnoreCase(selected);
            if (isCorrect) correct++;

            attemptAnswers.add(AttemptAnswer.builder()
                    .attempt(attempt).question(q)
                    .selectedOption(selected).correct(isCorrect).build());

            reviews.add(TestResultResponse.AnswerReviewResponse.builder()
                    .questionId(q.getId()).questionText(q.getQuestionText())
                    .imageUrl(q.getImageUrl())
                    .optionA(q.getOptionA()).optionB(q.getOptionB())
                    .optionC(q.getOptionC()).optionD(q.getOptionD())
                    .selectedOption(selected).correctOption(q.getCorrectOption())
                    .explanation(q.getExplanation()).correct(isCorrect).build());
        }

        int     total  = attempt.getTotalQuestions();
        int     score  = total > 0 ? (int) Math.round((correct * 100.0) / total) : 0;
        boolean passed = score >= passPercentage;

        attempt.setCorrectAnswers(correct);
        attempt.setScore(score);
        attempt.setPassed(passed);
        attempt.setCompletedAt(LocalDateTime.now());
        attempt.setTimeTakenSeconds(req.getTimeTakenSeconds());
        attempt.getAnswers().addAll(attemptAnswers);
        testAttemptRepository.save(attempt);
        log.info("Attempt {} submitted. Score: {}% | Passed: {}", attempt.getId(), score, passed);

        return TestResultResponse.builder()
                .attemptId(attempt.getId()).score(score)
                .totalQuestions(total).correctAnswers(correct)
                .passed(passed).passPercentage(passPercentage)
                .timeTakenSeconds(req.getTimeTakenSeconds())
                .completedAt(attempt.getCompletedAt())
                .answers(reviews).build();
    }

    // ── History ──────────────────────────────────────────────────────────────

    // Cache key includes page+size so different pages are cached independently
    @Cacheable(value = RedisConfig.CACHE_USER_HISTORY, key = "#user.id + '_' + #page + '_' + #size")
    @Transactional(readOnly = true)
    public UserHistoryResponse getUserHistory(User user, int page, int size) {
        Long   userId  = user.getId();
        long   total   = testAttemptRepository.countByUserId(userId);
        long   passed  = testAttemptRepository.countByUserIdAndPassedTrue(userId);
        long   failed  = testAttemptRepository.countByUserIdAndPassedFalse(userId);
        Double avg     = testAttemptRepository.findAverageScoreByUserId(userId);
        Integer best   = testAttemptRepository.findBestScoreByUserId(userId);

        Page<TestAttempt> attempts = testAttemptRepository
                .findByUserIdOrderByStartedAtDesc(userId, PageRequest.of(page, size));

        List<UserHistoryResponse.AttemptSummary> summaries = attempts.getContent().stream()
                .map(a -> UserHistoryResponse.AttemptSummary.builder()
                        .attemptId(a.getId()).score(a.getScore())
                        .totalQuestions(a.getTotalQuestions()).correctAnswers(a.getCorrectAnswers())
                        .passed(a.isPassed()).startedAt(a.getStartedAt())
                        .completedAt(a.getCompletedAt()).timeTakenSeconds(a.getTimeTakenSeconds())
                        .build())
                .collect(Collectors.toList());

        return UserHistoryResponse.builder()
                .totalAttempts(total).passedAttempts(passed).failedAttempts(failed)
                .averageScore(avg != null ? Math.round(avg * 10.0) / 10.0 : 0.0)
                .bestScore(best != null ? best : 0)
                .recentAttempts(summaries).build();
    }

    @Transactional(readOnly = true)
    public TestResultResponse getAttemptResult(Long attemptId, User user) {
        TestAttempt attempt = testAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new AppExceptions.ResourceNotFoundException("Attempt not found"));

        if (!attempt.getUser().getId().equals(user.getId()))
            throw new AppExceptions.InvalidTestStateException("Access denied to this attempt");

        List<TestResultResponse.AnswerReviewResponse> reviews = attempt.getAnswers().stream()
                .map(aa -> {
                    Question q = aa.getQuestion();
                    return TestResultResponse.AnswerReviewResponse.builder()
                            .questionId(q.getId()).questionText(q.getQuestionText())
                            .imageUrl(q.getImageUrl())
                            .optionA(q.getOptionA()).optionB(q.getOptionB())
                            .optionC(q.getOptionC()).optionD(q.getOptionD())
                            .selectedOption(aa.getSelectedOption()).correctOption(q.getCorrectOption())
                            .explanation(q.getExplanation()).correct(aa.isCorrect()).build();
                })
                .collect(Collectors.toList());

        return TestResultResponse.builder()
                .attemptId(attempt.getId()).score(attempt.getScore())
                .totalQuestions(attempt.getTotalQuestions()).correctAnswers(attempt.getCorrectAnswers())
                .passed(attempt.isPassed()).passPercentage(passPercentage)
                .timeTakenSeconds(attempt.getTimeTakenSeconds())
                .completedAt(attempt.getCompletedAt())
                .answers(reviews).build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private QuestionResponse toQuestionResponse(Question q) {
        return QuestionResponse.builder()
                .id(q.getId()).questionText(q.getQuestionText())
                .optionA(q.getOptionA()).optionB(q.getOptionB())
                .optionC(q.getOptionC()).optionD(q.getOptionD())
                .imageUrl(q.getImageUrl()).category(q.getCategory())
                .difficulty(q.getDifficulty()).build();
    }
}
