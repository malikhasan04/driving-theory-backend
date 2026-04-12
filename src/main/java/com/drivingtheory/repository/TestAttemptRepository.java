package com.drivingtheory.repository;

import com.drivingtheory.entity.TestAttempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TestAttemptRepository extends JpaRepository<TestAttempt, Long> {

    Page<TestAttempt> findByUserIdOrderByStartedAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    long countByUserIdAndPassedTrue(Long userId);

    long countByUserIdAndPassedFalse(Long userId);

    @Query("SELECT AVG(a.score) FROM TestAttempt a WHERE a.user.id = :userId AND a.completedAt IS NOT NULL")
    Double findAverageScoreByUserId(Long userId);

    @Query("SELECT MAX(a.score) FROM TestAttempt a WHERE a.user.id = :userId AND a.completedAt IS NOT NULL")
    Integer findBestScoreByUserId(Long userId);
}
