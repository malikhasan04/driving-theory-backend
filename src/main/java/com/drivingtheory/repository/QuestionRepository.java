package com.drivingtheory.repository;

import com.drivingtheory.entity.Question;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuestionRepository extends JpaRepository<Question, Long> {

    List<Question> findByActiveTrue();

    Page<Question> findByActiveTrue(Pageable pageable);

    @Query(value = "SELECT * FROM questions WHERE active = true ORDER BY RAND() LIMIT :limit",
           nativeQuery = true)
    List<Question> findRandomActiveQuestions(int limit);

    long countByActiveTrue();
}
