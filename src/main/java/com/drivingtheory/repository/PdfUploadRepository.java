package com.drivingtheory.repository;

import com.drivingtheory.entity.PdfUpload;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PdfUploadRepository extends JpaRepository<PdfUpload, Long> {
    Page<PdfUpload> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
