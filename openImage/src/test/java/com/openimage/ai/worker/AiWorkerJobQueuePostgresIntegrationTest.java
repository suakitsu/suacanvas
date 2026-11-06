package com.openimage.ai.worker;

import com.openimage.domain.entity.AiWorkerJobEntity;
import com.openimage.domain.mapper.AiWorkerJobMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AiWorkerJobQueuePostgresIntegrationTest {
    @Test
    void twoBackendInstancesCannotExceedGlobalProviderConcurrency() throws Exception {
        String url = System.getenv("AI_WORKER_TEST_DB_URL");
        String username = System.getenv("AI_WORKER_TEST_DB_USERNAME");
        String password = System.getenv("AI_WORKER_TEST_DB_PASSWORD");
        assumeTrue(url != null && !url.isBlank(), "AI_WORKER_TEST_DB_URL is not configured");

        DriverManagerDataSource admin = dataSource(url, username, password);
        String schema = "ai_worker_test_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(admin, schema);
        try {
            DriverManagerDataSource scoped = dataSource(withSchema(url, schema), username, password);
            JdbcTemplate jdbc = new JdbcTemplate(scoped);
            createTable(jdbc);
            insertQueued(jdbc, "awj-1", OffsetDateTime.now().minusSeconds(2));
            insertQueued(jdbc, "awj-2", OffsetDateTime.now().minusSeconds(1));

            AiWorkerJobMapper firstMapper = mapper(scoped);
            AiWorkerJobMapper secondMapper = mapper(scoped);
            TransactionTemplate firstTx = new TransactionTemplate(new DataSourceTransactionManager(scoped));
            TransactionTemplate secondTx = new TransactionTemplate(new DataSourceTransactionManager(scoped));
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<AiWorkerJobEntity> first = pool.submit(
                    () -> claimAfter(start, firstTx, firstMapper, "worker-a")
                );
                Future<AiWorkerJobEntity> second = pool.submit(
                    () -> claimAfter(start, secondTx, secondMapper, "worker-b")
                );
                start.countDown();

                List<AiWorkerJobEntity> claimed = Stream.of(first.get(), second.get())
                    .filter(item -> item != null)
                    .toList();

                assertThat(claimed).extracting(AiWorkerJobEntity::getJobId).containsExactly("awj-1");
                assertThat(count(jdbc, "status = 'leased'")).isEqualTo(1);
                assertThat(count(jdbc, "status = 'queued'")).isEqualTo(1);

                jdbc.update("UPDATE ai_worker_jobs SET status = 'succeeded', completed_at = now() WHERE job_id = 'awj-1'");
                AiWorkerJobEntity next = secondTx.execute(
                    status -> claimWithLimit(secondMapper, "worker-b")
                );
                assertThat(next).isNotNull();
                assertThat(next.getJobId()).isEqualTo("awj-2");
            } finally {
                pool.shutdownNow();
            }
        } finally {
            dropSchema(admin, schema);
        }
    }

    @Test
    void expiredLeaseCanBeRequeuedButOldLeaseNoLongerOccupiesSlot() throws Exception {
        String url = System.getenv("AI_WORKER_TEST_DB_URL");
        String username = System.getenv("AI_WORKER_TEST_DB_USERNAME");
        String password = System.getenv("AI_WORKER_TEST_DB_PASSWORD");
        assumeTrue(url != null && !url.isBlank(), "AI_WORKER_TEST_DB_URL is not configured");

        DriverManagerDataSource admin = dataSource(url, username, password);
        String schema = "ai_worker_lease_test_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(admin, schema);
        try {
            DriverManagerDataSource scoped = dataSource(withSchema(url, schema), username, password);
            JdbcTemplate jdbc = new JdbcTemplate(scoped);
            createTable(jdbc);
            insertQueued(jdbc, "awj-expire", OffsetDateTime.now().minusSeconds(1));
            AiWorkerJobMapper mapper = mapper(scoped);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(scoped));

            AiWorkerJobEntity first = tx.execute(status -> mapper.claimNext(
                "local-cliproxy-image",
                "image",
                "worker-a",
                "old-hash",
                OffsetDateTime.now().minusSeconds(1),
                OffsetDateTime.now().minusSeconds(1)
            ));
            assertThat(first).isNotNull();
            assertThat(mapper.expireLeases()).isEqualTo(1);
            assertThat(mapper.countActiveByProvider("local-cliproxy-image")).isZero();
            assertThat(mapper.requeueExpired("local-cliproxy-image")).isEqualTo(1);

            AiWorkerJobEntity retried = tx.execute(status -> mapper.claimNext(
                "local-cliproxy-image",
                "image",
                "worker-b",
                "new-hash",
                OffsetDateTime.now().plusMinutes(2),
                OffsetDateTime.now().plusMinutes(3)
            ));
            assertThat(retried).isNotNull();
            assertThat(retried.getAttempt()).isEqualTo(2);
            assertThat(retried.getWorkerId()).isEqualTo("worker-b");
        } finally {
            dropSchema(admin, schema);
        }
    }

    private AiWorkerJobEntity claimAfter(
        CountDownLatch start,
        TransactionTemplate transaction,
        AiWorkerJobMapper mapper,
        String workerId
    ) throws InterruptedException {
        start.await();
        return transaction.execute(status -> claimWithLimit(mapper, workerId));
    }

    private AiWorkerJobEntity claimWithLimit(AiWorkerJobMapper mapper, String workerId) {
        mapper.lockProvider("local-cliproxy-image");
        if (mapper.countActiveByProvider("local-cliproxy-image") >= 1) {
            return null;
        }
        return mapper.claimNext(
            "local-cliproxy-image",
            "image",
            workerId,
            "lease-hash-" + workerId,
            OffsetDateTime.now().plusMinutes(2),
            OffsetDateTime.now().plusMinutes(3)
        );
    }

    private AiWorkerJobMapper mapper(DriverManagerDataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("MyBatis session factory was not created");
        }
        factory.getConfiguration().setMapUnderscoreToCamelCase(true);
        factory.getConfiguration().addMapper(AiWorkerJobMapper.class);
        return new SqlSessionTemplate(factory).getMapper(AiWorkerJobMapper.class);
    }

    private void createTable(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE ai_worker_jobs (
              job_id VARCHAR(64) PRIMARY KEY,
              business_task_id VARCHAR(160) NOT NULL,
              provider_id VARCHAR(96) NOT NULL,
              capability VARCHAR(16) NOT NULL,
              protocol VARCHAR(48) NOT NULL,
              model VARCHAR(160) NOT NULL,
              request_json JSONB NOT NULL,
              input_files_json JSONB NOT NULL DEFAULT '[]'::jsonb,
              request_hash VARCHAR(64) NOT NULL,
              status VARCHAR(24) NOT NULL,
              worker_id VARCHAR(120),
              lease_token VARCHAR(128),
              lease_until TIMESTAMPTZ,
              lease_grace_until TIMESTAMPTZ,
              attempt INTEGER NOT NULL DEFAULT 0,
              result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
              error_code VARCHAR(80),
              error_message VARCHAR(500),
              completion_idempotency_key VARCHAR(128),
              completion_request_hash VARCHAR(64),
              created_at TIMESTAMPTZ NOT NULL,
              updated_at TIMESTAMPTZ NOT NULL,
              completed_at TIMESTAMPTZ
            )
            """);
    }

    private void insertQueued(JdbcTemplate jdbc, String jobId, OffsetDateTime createdAt) {
        jdbc.update("""
            INSERT INTO ai_worker_jobs(
              job_id, business_task_id, provider_id, capability, protocol, model,
              request_json, input_files_json, request_hash, status, attempt,
              result_json, created_at, updated_at
            )
            VALUES (?, ?, 'local-cliproxy-image', 'image', 'openai_images_generations',
                    'gpt-image-2', '{}'::jsonb, '[]'::jsonb, ?, 'queued', 0,
                    '{}'::jsonb, ?, ?)
            """,
            jobId,
            "business-" + jobId,
            "hash-" + jobId,
            createdAt,
            createdAt
        );
    }

    private int count(JdbcTemplate jdbc, String condition) {
        Integer value = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ai_worker_jobs WHERE " + condition,
            Integer.class
        );
        return value == null ? 0 : value;
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

    private void createSchema(DriverManagerDataSource dataSource, String schema) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private void dropSchema(DriverManagerDataSource dataSource, String schema) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
