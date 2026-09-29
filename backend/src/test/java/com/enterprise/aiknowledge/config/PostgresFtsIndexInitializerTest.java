package com.enterprise.aiknowledge.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresFtsIndexInitializer} covering database product detection,
 * H2 isolation, 'oid' column migration to 'text', existing index detection, index creation,
 * and fail-fast exception propagation.
 */
class PostgresFtsIndexInitializerTest {

    private JdbcTemplate mockJdbcTemplate;
    private PostgresFtsIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        mockJdbcTemplate = mock(JdbcTemplate.class);
        initializer = new PostgresFtsIndexInitializer(mockJdbcTemplate, "english");
    }

    private void mockDatabaseProductName(String productName) {
        when(mockJdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            Connection mockConn = mock(Connection.class);
            DatabaseMetaData mockMetaData = mock(DatabaseMetaData.class);
            when(mockConn.getMetaData()).thenReturn(mockMetaData);
            when(mockMetaData.getDatabaseProductName()).thenReturn(productName);
            return callback.doInConnection(mockConn);
        });
    }

    @Test
    @DisplayName("Non-PostgreSQL databases (e.g. H2) safely skip index creation")
    void nonPostgresDatabaseSkipsIndexCreation() {
        mockDatabaseProductName("H2");

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(mockJdbcTemplate, never()).execute(startsWith("CREATE INDEX"));
        verify(mockJdbcTemplate, never()).execute(startsWith("ALTER TABLE"));
        verify(mockJdbcTemplate, never()).query(anyString(), any(RowMapper.class));
    }

    @Test
    @DisplayName("PostgreSQL with existing 'oid' column migrates column to 'text' and creates index")
    void postgresMigratesOidColumnAndCreatesIndex() {
        mockDatabaseProductName("PostgreSQL");

        // Simulate information_schema returning 'oid'
        when(mockJdbcTemplate.query(contains("information_schema.columns"), any(RowMapper.class)))
                .thenReturn(List.of("oid"));
        // Simulate pg_indexes returning empty (index does not exist yet)
        when(mockJdbcTemplate.query(contains("pg_indexes"), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        initializer.run(new DefaultApplicationArguments(new String[0]));

        // Verify column migration
        verify(mockJdbcTemplate).execute("ALTER TABLE document_chunks ALTER COLUMN text TYPE text;");
        // Verify index creation with coalesce
        verify(mockJdbcTemplate).execute(contains("CREATE INDEX IF NOT EXISTS idx_document_chunks_fts ON document_chunks USING gin (to_tsvector('english', coalesce(text, '')));"));
    }

    @Test
    @DisplayName("PostgreSQL with already existing index verifies compatibility and skips CREATE INDEX")
    void postgresWithExistingIndexSkipsCreate() {
        mockDatabaseProductName("PostgreSQL");

        // Column is already 'text'
        when(mockJdbcTemplate.query(contains("information_schema.columns"), any(RowMapper.class)))
                .thenReturn(List.of("text"));
        // Index already exists
        when(mockJdbcTemplate.query(contains("pg_indexes"), any(RowMapper.class)))
                .thenReturn(List.of("idx_document_chunks_fts"));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(mockJdbcTemplate, never()).execute(startsWith("ALTER TABLE"));
        verify(mockJdbcTemplate, never()).execute(startsWith("CREATE INDEX"));
    }

    @Test
    @DisplayName("PostgreSQL failure throws IllegalStateException and does not silently continue")
    void postgresFailureThrowsIllegalStateException() {
        mockDatabaseProductName("PostgreSQL");

        when(mockJdbcTemplate.query(contains("information_schema.columns"), any(RowMapper.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("task", "sql", new SQLException("relation document_chunks does not exist")));

        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to initialize PostgreSQL Full-Text Search index")
                .hasMessageContaining("relation document_chunks does not exist");
    }
}
