package com.openimage.ai.worker;

import com.openimage.domain.mapper.AiWorkerJobMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkerJobMapperContractTest {
    @Test
    void claimContractUsesPostgresLocksStrictFifoAndConditionalUpdate() throws Exception {
        Method providerLock = AiWorkerJobMapper.class.getMethod("lockProvider", String.class);
        Method workerLock = AiWorkerJobMapper.class.getMethod("lockWorker", String.class);
        Method claim = AiWorkerJobMapper.class.getMethod(
            "claimNext",
            String.class,
            String.class,
            String.class,
            String.class,
            java.time.OffsetDateTime.class,
            java.time.OffsetDateTime.class
        );

        String providerSql = sql(providerLock);
        String workerSql = sql(workerLock);
        String claimSql = sql(claim);

        assertThat(providerSql).contains("pg_advisory_xact_lock", "providerid");
        assertThat(workerSql).contains("pg_advisory_xact_lock", "workerid");
        assertThat(claimSql)
            .contains("status = 'queued'")
            .contains("order by created_at asc")
            .contains("for update skip locked")
            .contains("status = 'leased'")
            .contains("lease_grace_until")
            .contains("attempt = attempt + 1")
            .contains("returning job.*");
    }

    private String sql(Method method) {
        return String.join(" ", method.getAnnotation(Select.class).value())
            .replaceAll("\\s+", " ")
            .toLowerCase();
    }
}
