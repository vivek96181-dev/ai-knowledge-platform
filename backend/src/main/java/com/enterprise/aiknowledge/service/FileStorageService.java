package com.enterprise.aiknowledge.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction for physical file storage operations.
 *
 * <p>Isolates storage mechanics (local filesystem, S3, Blob storage) from
 * the business logic in {@link DocumentService}.</p>
 */
public interface FileStorageService {

    /**
     * Stores an uploaded file under the given stored filename.
     *
     * @param file           the uploaded file from the request
     * @param storedFilename unique generated filename for storage
     * @return storage path (e.g. absolute or relative path to saved file)
     */
    String storeFile(MultipartFile file, String storedFilename);

    /**
     * Deletes a file from physical storage.
     *
     * @param storagePath storage path of the file to delete
     */
    void deleteFile(String storagePath);

    /**
     * Checks if a file exists in physical storage.
     *
     * @param storagePath storage path to check
     * @return true if the file exists
     */
    boolean fileExists(String storagePath);
}
