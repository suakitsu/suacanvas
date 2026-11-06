package com.openimage.ai.worker;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AiWorkerFlywayMigrationPostgresIntegrationTest {
    @Test
    void v40AndV41MigrateCleanlyAndCreateWorkerProtocolSchema() throws Exception {
        String url = System.getenv("AI_WORKER_TEST_DB_URL");
        String username = System.getenv("AI_WORKER_TEST_DB_USERNAME");
        String password = System.getenv("AI_WORKER_TEST_DB_PASSWORD");
        assumeTrue(url != null && !url.isBlank(), "AI_WORKER_TEST_DB_URL is not configured");

        DriverManagerDataSource admin = dataSource(url, username, password);
        String schema = "ai_worker_migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                .dataSource(url, username, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();

            DriverManagerDataSource scoped = dataSource(withSchema(url, schema), username, password);
            JdbcTemplate jdbc = new JdbcTemplate(scoped);

            List<String> workerVersions = jdbc.queryForList(
                """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE version IN ('40', '41')
                      AND success = TRUE
                    ORDER BY installed_rank
                    """,
                String.class
            );
            assertThat(workerVersions).containsExactly("40", "41");

            List<String> tables = jdbc.queryForList(
                """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = current_schema()
                      AND table_name IN (
                        'ai_worker_tokens',
                        'ai_worker_instances',
                        'ai_worker_jobs',
                        'ai_worker_artifacts'
                      )
                    ORDER BY table_name
                    """,
                String.class
            );
            assertThat(tables).containsExactly(
                "ai_worker_artifacts",
                "ai_worker_instances",
                "ai_worker_jobs",
                "ai_worker_tokens"
            );

            List<String> hardenedColumns = jdbc.queryForList(
                """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'ai_worker_jobs'
                      AND column_name IN (
                        'lease_grace_until',
                        'completion_idempotency_key',
                        'completion_request_hash'
                      )
                    ORDER BY column_name
                    """,
                String.class
            );
            assertThat(hardenedColumns).containsExactly(
                "completion_idempotency_key",
                "completion_request_hash",
                "lease_grace_until"
            );

            Integer artifactIdempotencyIndex = jdbc.queryForObject(
                """
                    SELECT COUNT(*)
                    FROM pg_indexes
                    WHERE schemaname = current_schema()
                      AND tablename = 'ai_worker_artifacts'
                      AND indexname = 'uk_ai_worker_artifacts_job_idempotency'
                    """,
                Integer.class
            );
            assertThat(artifactIdempotencyIndex).isEqualTo(1);
        } finally {
            dropSchema(admin, schema);
        }
    }

    private DriverManagerDataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username == null ? "" : username);
        dataSource.setPassword(password == null ? "" : password);
        return dataSource;
    }

    private String withSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private void dropSchema(DriverManagerDataSource dataSource, String schema) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
