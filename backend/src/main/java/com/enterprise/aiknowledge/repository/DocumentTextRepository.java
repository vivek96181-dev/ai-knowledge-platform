package com.enterprise.aiknowledge.repository;

import com.enterprise.aiknowledge.model.DocumentText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DocumentText} entity operations.
 */
@Repository
public interface DocumentTextRepository extends JpaRepository<DocumentText, Long> {

    /**
     * Finds extracted text entity by associated document ID.
     *
     * @param documentId document primary key
     * @return optional containing DocumentText if found
     */
    Optional<DocumentText> findByDocumentId(Long documentId);

    /**
     * Deletes extracted text record by associated document ID.
     *
     * @param documentId document primary key
     */
    void deleteByDocumentId(Long documentId);
}
