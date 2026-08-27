package com.enterprise.aiknowledge.repository;

import com.enterprise.aiknowledge.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link Document} entity.
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * Finds all documents owned by a specific user.
     *
     * @param ownerId primary key of the owner user
     * @return list of documents belonging to the user
     */
    List<Document> findByOwnerId(Long ownerId);

    /**
     * Finds a specific document by its ID and owner ID.
     *
     * @param id      primary key of the document
     * @param ownerId primary key of the owner user
     * @return optional containing the document if found and owned by the user
     */
    Optional<Document> findByIdAndOwnerId(Long id, Long ownerId);
}
