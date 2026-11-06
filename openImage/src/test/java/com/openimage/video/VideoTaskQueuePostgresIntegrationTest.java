package com.openimage.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.VideoTaskMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoTaskQueuePostgresIntegrationTest {
    @Test
    void twoThreadsAndTwoInstancesNeverExceedGlobalAgnesConcurrency() throws Exception {
        String url = System.getenv("VIDEO_QUEUE_TEST_DB_URL");
        String username = System.getenv("VIDEO_QUEUE_TEST_DB_USERNAME");
        String password = System.getenv("VIDEO_QUEUE_TEST_DB_PASSWORD");
        assumeTrue(url != null && !url.isBlank(), "VIDEO_QUEUE_TEST_DB_URL is not configured");

        DriverManagerDataSource adminDataSource = dataSource(url, username, password);
        String schema = "video_queue_test_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(adminDataSource, schema);
        try {
            DriverManagerDataSource scopedDataSource = dataSource(withSchema(url, schema), username, password);
            JdbcTemplate jdbc = new JdbcTemplate(scopedDataSource);
            jdbc.execute("""
                CREATE TABLE video_tasks (
                  id VARCHAR(64) PRIMARY KEY,
                  provider VARCHAR(64) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  progress INTEGER NOT NULL DEFAULT 0,
                  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                  provider_attempts JSONB NOT NULL DEFAULT '[]'::jsonb,
                  created_at TIMESTAMPTZ NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL,
                  deleted SMALLINT NOT NULL DEFAULT 0
                )
                """);
            insertQueued(jdbc, "vid_1", OffsetDateTime.now().minusSeconds(3));
            insertQueued(jdbc, "vid_2", OffsetDateTime.now().minusSeconds(2));
            insertQueued(jdbc, "vid_3", OffsetDateTime.now().minusSeconds(1));

            VideoTaskMapper mapper = mapper(scopedDataSource);
            VideoConfigService config = mock(VideoConfigService.class);
            when(config.current()).thenReturn(runtime(1));
            when(config.dispatchableAgnesKeys()).thenReturn(List.of(key()));
            ObjectMapper json = new ObjectMapper();
            VideoTaskQueueService firstInstance = new VideoTaskQueueService(mapper, config, jdbc, json);
            VideoTaskQueueService secondInstance = new VideoTaskQueueService(mapper, config, jdbc, json);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(scopedDataSource));

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<VideoTaskEntity> first = pool.submit(() -> claimAfter(start, transactions, firstInstance));
                Future<VideoTaskEntity> second = pool.submit(() -> claimAfter(start, transactions, secondInstance));
                start.countDown();
                List<VideoTaskEntity> claimed = Stream.of(first.get(), second.get())
                    .filter(task -> task != null)
                    .toList();

                assertThat(claimed).extracting(VideoTaskEntity::getId).containsExactly("vid_1");
                assertThat(count(jdbc, "status = 'submitting'")).isEqualTo(1);
                assertThat(count(jdbc, "status = 'queued'")).isEqualTo(2);

                jdbc.update("UPDATE video_tasks SET status = 'completed', updated_at = now() WHERE id = 'vid_1'");
                VideoTaskEntity next = transactions.execute(status -> secondInstance.claimNextAgnes());
                assertThat(next).isNotNull();
                assertThat(next.getId()).isEqualTo("vid_2");
                assertThat(count(jdbc, "status = 'submitting'")).isEqualTo(1);
                assertThat(count(jdbc, "status = 'queued'")).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        } finally {
            dropSchema(adminDataSource, schema);
        }
    }

    private VideoTaskEntity claimAfter(
        CountDownLatch start,
        TransactionTemplate transactions,
        VideoTaskQueueService queue
    ) throws InterruptedException {
        start.await();
        return transactions.execute(status -> queue.claimNextAgnes());
    }

    private VideoTaskMapper mapper(DriverManagerDataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("MyBatis session factory was not created");
        factory.getConfiguration().addMapper(VideoTaskMapper.class);
        return new SqlSessionTemplate(factory).getMapper(VideoTaskMapper.class);
    }

    private void insertQueued(JdbcTemplate jdbc, String id, OffsetDateTime createdAt) {
        jdbc.update(
            "INSERT INTO video_tasks(id, provider, status, progress, created_at, updated_at, deleted) VALUES (?, 'agnes', 'queued', 0, ?, ?, 0)",
            id, createdAt, createdAt
        );
    }

    private int count(JdbcTemplate jdbc, String condition) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM video_tasks WHERE " + condition, Integer.class);
        return value == null ? 0 : value;
    }

    private void createSchema(DriverManagerDataSource dataSource, String schema) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private void dropSchema(DriverManagerDataSource dataSource, String schema) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
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

    private VideoConfigService.RuntimeSettings runtime(int concurrency) {
        return new VideoConfigService.RuntimeSettings(
            true, false, true, true, true,
            new VideoConfigService.TierSettings(true, 10, "720p", 900),
            new VideoConfigService.TierSettings(true, 10, "720p", 1200),
            15, 40, concurrency, 3, 5, 30
        );
    }

    private VideoConfigService.AgnesKeyRuntime key() {
        return new VideoConfigService.AgnesKeyRuntime(
            "key-1", "Key 1", true, 1, "secret", "fingerprint", "admin", true, false
        );
    }
}
