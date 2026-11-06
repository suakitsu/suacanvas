package com.openimage.ai.worker;

import com.openimage.domain.entity.AiWorkerTokenEntity;
import com.openimage.domain.mapper.AiWorkerTokenMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWorkerTokenServiceTest {
    @Test
    void createsHighEntropyTokenAndStoresOnlyPepperedHash() {
        AiWorkerTokenMapper mapper = mock(AiWorkerTokenMapper.class);
        AiWorkerTokenService service = new AiWorkerTokenService(
            mapper,
            new AiWorkerProperties(
                "https://api.example.com",
                "pepper-for-worker-token-tests-32-characters",
                "signing-secret-for-tests-32-characters",
                60,
                300,
                25L * 1024L * 1024L,
                15
            )
        );

        AiWorkerTokenService.CreatedToken created = service.create("Desktop Worker");
        ArgumentCaptor<AiWorkerTokenEntity> rowCaptor = ArgumentCaptor.forClass(AiWorkerTokenEntity.class);
        verify(mapper).insert(rowCaptor.capture());
        AiWorkerTokenEntity stored = rowCaptor.getValue();

        assertThat(created.token()).startsWith("ppw_");
        assertThat(Base64.getUrlDecoder().decode(created.token().substring(4))).hasSize(32);
        assertThat(stored.getTokenHash()).hasSize(64).doesNotContain(created.token());
        assertThat(stored.getTokenPrefix()).isEqualTo(created.token().substring(0, 12));
        assertThat(created.workerToken()).doesNotContainKeys("token", "token_hash");
    }

    @Test
    void authenticateUsesHashAndNeverQueriesByRawToken() {
        AiWorkerTokenMapper mapper = mock(AiWorkerTokenMapper.class);
        AiWorkerTokenEntity stored = new AiWorkerTokenEntity();
        stored.setId("awt-1");
        stored.setName("Worker");
        stored.setTokenPrefix("ppw_example");
        when(mapper.findActiveByHash(anyString())).thenReturn(stored);
        AiWorkerTokenService service = new AiWorkerTokenService(
            mapper,
            new AiWorkerProperties(
                "https://api.example.com",
                "pepper-for-worker-token-tests-32-characters",
                "signing-secret-for-tests-32-characters",
                60,
                300,
                25L * 1024L * 1024L,
                15
            )
        );

        AiWorkerPrincipal principal = service.authenticate("ppw_raw-token-value");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).findActiveByHash(hash.capture());

        assertThat(hash.getValue()).hasSize(64).doesNotContain("raw-token-value");
        assertThat(principal.tokenId()).isEqualTo("awt-1");
        verify(mapper).touch("awt-1");
    }

    @Test
    void refusesWeakTokenPepper() {
        AiWorkerTokenService service = new AiWorkerTokenService(
            mock(AiWorkerTokenMapper.class),
            new AiWorkerProperties(
                "https://api.example.com",
                "too-short",
                "signing-secret-for-tests-32-characters",
                60,
                300,
                25L * 1024L * 1024L,
                15
            )
        );

        assertThatThrownBy(() -> service.create("Worker"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32");
    }
}
