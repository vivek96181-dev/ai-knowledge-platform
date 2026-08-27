package com.enterprise.aiknowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;

/**
 * Local filesystem implementation of {@link FileStorageService}.
 *
 * <p>Stores uploaded files in a configurable local directory (default: {@code uploads}).</p>
 */
@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path storageLocation;

    public LocalFileStorageService(@Value("${file.upload-dir:uploads}") String uploadDir) {
        this.storageLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageLocation);
        } catch (IOException ex) {
            throw new RuntimeException("Could not create storage directory: " + this.storageLocation, ex);
        }
    }

    @Override
    public String storeFile(MultipartFile file, String storedFilename) {
        try {
            if (file.isEmpty()) {
                throw new RuntimeException("Cannot store empty file.");
            }
            Path targetLocation = this.storageLocation.resolve(storedFilename).normalize();

            // Guard against Path Traversal attacks
            if (!targetLocation.getParent().equals(this.storageLocation)) {
                throw new RuntimeException("Cannot store file outside current storage directory.");
            }

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetLocation, StandardCopyOption.REPLACE_EXISTING);
            }
            return targetLocation.toString();
        } catch (IOException ex) {
            throw new RuntimeException("Failed to store file: " + storedFilename, ex);
        }
    }

    @Override
    public void deleteFile(String storagePath) {
        try {
            Path filePath = Paths.get(storagePath).toAbsolutePath().normalize();
            Files.deleteIfExists(filePath);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to delete file at: " + storagePath, ex);
        }
    }

    @Override
    public boolean fileExists(String storagePath) {
        Path filePath = Paths.get(storagePath).toAbsolutePath().normalize();
        return Files.exists(filePath);
    }

    public Path getStorageLocation() {
        return storageLocation;
    }
}
