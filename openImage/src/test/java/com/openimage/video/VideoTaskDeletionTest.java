package com.openimage.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import com.openimage.storage.LocalStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoTaskDeletionTest {
    @Test
    void terminalTaskIsActuallySoftDeleted() {
        Fixture fixture = new Fixture("completed");
        when(fixture.tasks.hideTerminalOwned(eq("video-1"), eq("user-1"), any(OffsetDateTime.class)))
            .thenReturn(1);

        assertThat(fixture.service.delete(fixture.user, "video-1"))
            .containsEntry("task_id", "video-1")
            .containsEntry("hidden", true);
        verify(fixture.tasks).hideTerminalOwned(eq("video-1"), eq("user-1"), any(OffsetDateTime.class));
    }

    @Test
    void activeTaskCannotBeDeletedOrLoseItsResult() {
        Fixture fixture = new Fixture("generating");

        assertThatThrownBy(() -> fixture.service.delete(fixture.user, "video-1"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("完成后才能删除");
        verify(fixture.tasks, never()).hideTerminalOwned(any(), any(), any());
    }

    @Test
    void zeroRowUpdateIsNotReportedAsSuccessfulDeletion() {
        Fixture fixture = new Fixture("failed");
        when(fixture.tasks.hideTerminalOwned(eq("video-1"), eq("user-1"), any(OffsetDateTime.class)))
            .thenReturn(0);

        assertThatThrownBy(() -> fixture.service.delete(fixture.user, "video-1"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("请刷新后重试");
    }

    private static final class Fixture {
        private final VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        private final CurrentUser user = new CurrentUser("user-1", "user@example.com", "user");
        private final VideoTaskService service;

        private Fixture(String status) {
            VideoTaskEntity task = new VideoTaskEntity();
            task.setId("video-1");
            task.setUserId("user-1");
            task.setStatus(status);
            task.setHiddenByUser(0);
            task.setDeleted(0);
            when(tasks.selectById("video-1")).thenReturn(task);

            service = new VideoTaskService(
                tasks,
                mock(UserMapper.class),
                mock(CreditLedgerMapper.class),
                mock(LocalStorageService.class),
                mock(VideoConfigService.class),
                mock(RiskControlService.class),
                new ObjectMapper().findAndRegisterModules()
            );
        }
    }
}
