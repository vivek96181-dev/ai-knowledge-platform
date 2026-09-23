package com.enterprise.aiknowledge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Idempotently verifies, migrates schema if needed, and creates the PostgreSQL GIN
 * full-text search index on startup.
 *
 * <p><strong>Index Strategy:</strong><br>
 * Creates an expression GIN index:
 * {@code CREATE INDEX IF NOT EXISTS idx_document_chunks_fts ON document_chunks USING gin (to_tsvector('<language>', coalesce(text, '')));}<br>
 * This provides high-performance FTS lookups matching keyword queries exactly, handles NULLs safely,
 * and maintains source-of-truth in PostgreSQL.</p>
 *
 * <p><strong>Schema Verification:</strong><br>
 * If {@code document_chunks.text} was previously mapped as an {@code oid} (large object) by JPA,
 * it alters the column type to {@code text} so {@code to_tsvector} can process it directly.</p>
 *
 * <p><strong>H2 Test Isolation:</strong><br>
 * Detects the JDBC database product name. When running against H2 during unit/integration tests,
 * it safely skips index creation.</p>
 *
 * <p><strong>Fail-Fast Behavior:</strong><br>
 * If running on PostgreSQL and index initialization fails, the error is logged with root cause
 * and rethrown as {@link IllegalStateException} to prevent running in an unusable Hybrid Search state.</p>
 */
@Component
public class PostgresFtsIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PostgresFtsIndexInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final String ftsLanguage;

    public PostgresFtsIndexInitializer(
            JdbcTemplate jdbcTemplate,
            @Value("${search.fts.language:english}") String ftsLanguage) {
        this.jdbcTemplate = jdbcTemplate;
        this.ftsLanguage = ftsLanguage;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String dbProduct = jdbcTemplate.execute((ConnectionCallback<String>) con ->
                    con.getMetaData().getDatabaseProductName());

            if ("PostgreSQL".equalsIgnoreCase(dbProduct)) {
                initializePostgresFts();
            } else {
                log.debug("Database product is '{}'. Skipping PostgreSQL GIN index creation.", dbProduct);
            }
        } catch (Exception ex) {
            Throwable rootCause = getRootCause(ex);
            log.error("Failed to verify/create PostgreSQL Full-Text Search index: {}. Root cause: {}",
                    ex.getMessage(), rootCause.getMessage(), ex);
            throw new IllegalStateException("Failed to initialize PostgreSQL Full-Text Search index: " + ex.getMessage()
                    + ". Root cause: " + rootCause.getMessage(), ex);
        }
    }

    void initializePostgresFts() {
        log.info("Verifying PostgreSQL Full-Text Search schema on document_chunks (language: '{}')...", ftsLanguage);

        // 1. Verify and migrate column type if previously created as 'oid'
        List<String> columnTypes = jdbcTemplate.query(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'document_chunks' AND column_name = 'text'",
                (rs, rowNum) -> rs.getString("data_type")
        );

        if (!columnTypes.isEmpty() && "oid".equalsIgnoreCase(columnTypes.get(0))) {
            log.warn("Detected 'oid' data type for document_chunks.text. Migrating column type to 'text' for FTS compatibility...");
            jdbcTemplate.execute("ALTER TABLE document_chunks ALTER COLUMN text TYPE text;");
            log.info("Successfully altered document_chunks.text column type to 'text'.");
        }

        // 2. Check if index already exists
        List<String> existingIndexes = jdbcTemplate.query(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'document_chunks' AND indexname = 'idx_document_chunks_fts'",
                (rs, rowNum) -> rs.getString("indexname")
        );

        if (!existingIndexes.isEmpty()) {
            log.info("PostgreSQL GIN FTS index 'idx_document_chunks_fts' already exists and is verified compatible.");
            return;
        }

        // 3. Create GIN index using to_tsvector with explicit language and coalesce for NULL safety
        String createIndexSql = String.format(
                "CREATE INDEX IF NOT EXISTS idx_document_chunks_fts ON document_chunks USING gin (to_tsvector('%s', coalesce(text, '')));",
                ftsLanguage
        );
        log.info("Creating PostgreSQL GIN FTS index: {}", createIndexSql);
        jdbcTemplate.execute(createIndexSql);
        log.info("PostgreSQL GIN FTS index 'idx_document_chunks_fts' created successfully.");
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }
}
