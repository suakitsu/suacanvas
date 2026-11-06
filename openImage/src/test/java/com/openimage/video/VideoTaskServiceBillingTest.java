package com.openimage.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import com.openimage.storage.LocalStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoTaskServiceBillingTest {
    @Test
    void paidVideoIsIdempotentAndOnlyFailureCanRefundBeforeSuccessfulRecovery() throws Exception {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        UserMapper users = mock(UserMapper.class);
        CreditLedgerMapper ledgers = mock(CreditLedgerMapper.class);
        LocalStorageService storage = mock(LocalStorageService.class);
        VideoConfigService config = mock(VideoConfigService.class);
        RiskControlService riskControl = mock(RiskControlService.class);
        VideoConfigService.RuntimeSettings runtime = new VideoConfigService.RuntimeSettings(
            true,
            true,
            true,
            true,
            true,
            new VideoConfigService.TierSettings(true, 10, "720p", 900),
            new VideoConfigService.TierSettings(true, 10, "720p", 1200),
            15,
            40,
            1,
            3,
            5,
            30
        );
        when(config.current()).thenReturn(runtime);
        when(config.normalizeMode(anyString())).thenReturn("standard");
        when(config.assignmentFor(eq("standard"), anyString(), anyString())).thenReturn(
            new VideoConfigService.ProviderAssignment("agnes", "agnes-video-v2.0", "agnes_selected")
        );
        when(config.billableCreditsFor("standard", "agnes")).thenReturn(900);

        UserEntity account = new UserEntity();
        account.setId("user-1");
        account.setCredits(2000);
        account.setDeleted(0);
        when(users.lockById("user-1")).thenReturn(account);

        AtomicReference<VideoTaskEntity> insertedTask = new AtomicReference<>();
        doAnswer(invocation -> {
            insertedTask.set(invocation.getArgument(0));
            return 1;
        }).when(tasks).insert(any(VideoTaskEntity.class));
        when(tasks.findByUserAndIdempotencyKey("user-1", "request-key-0000001"))
            .thenAnswer(invocation -> insertedTask.get());

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        VideoTaskService service = new VideoTaskService(
            tasks,
            users,
            ledgers,
            storage,
            config,
            riskControl,
            json
        );
        CurrentUser currentUser = new CurrentUser("user-1", "user@example.com", "user");

        VideoDtos.VideoTaskView created = service.create(
            currentUser,
            "商品演示",
            "展示商品外观和使用场景",
            "9:16",
            "standard",
            null,
            null,
            null,
            null,
            null,
            List.of("https://example.com/product.jpg"),
            List.of(),
            "request-key-0000001"
        );

        assertThat(account.getCredits()).isEqualTo(1100);
        assertThat(created.creditsCost()).isEqualTo(900);
        assertThat(created.provider()).isEqualTo("agnes");
        assertThat(insertedTask.get().getCreditsCost()).isEqualTo(900);
        assertThat(insertedTask.get().getIdempotencyKey()).isEqualTo("request-key-0000001");

        VideoDtos.VideoTaskView replayed = service.create(
            currentUser,
            "商品演示",
            "展示商品外观和使用场景",
            "9:16",
            "standard",
            null,
            null,
            null,
            null,
            null,
            List.of("https://example.com/product.jpg"),
            List.of(),
            "request-key-0000001"
        );
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(account.getCredits()).isEqualTo(1100);

        insertedTask.get().setStatus("completed");
        insertedTask.get().setActualCost(new BigDecimal("0.012300"));
        insertedTask.get().setActualWidth(704);
        insertedTask.get().setActualHeight(1280);
        insertedTask.get().setActualSeconds(new BigDecimal("10.04"));
        insertedTask.get().setMetadata("""
            {
              "mock_mode":true,
              "mapped_width":704,
              "mapped_height":1280,
              "seconds":"10.04",
              "actual_cost":"0.012300",
              "upstream_cost":"0.001811",
              "upstream_cost_unit":"USD",
              "cost_conversion_rate":"6.791100",
              "cost_source":"upstream_cost"
            }
            """);
        String publicJson = json.writeValueAsString(service.view(insertedTask.get()));
        assertThat(publicJson)
            .doesNotContain(
                "\"actual_cost\":", "\"actual_width\":", "\"actual_height\":", "\"actual_seconds\":",
                "\"mapped_width\":", "\"mapped_height\":", "\"seconds\":",
                "\"upstream_cost\":", "\"upstream_cost_unit\":", "\"cost_conversion_rate\":", "\"cost_source\":"
            )
            .contains("\"metadata\":{\"mock_mode\":true}");

        when(tasks.lockById(created.id())).thenReturn(insertedTask.get());
        service.refundIfNeeded(created.id(), "错误的成功任务退款调用");
        assertThat(account.getCredits()).isEqualTo(1100);
        assertThat(insertedTask.get().getRefundedAt()).isNull();

        insertedTask.get().setStatus("failed");
        service.refundIfNeeded(created.id(), "上游生成失败");
        service.refundIfNeeded(created.id(), "重复失败通知");

        assertThat(account.getCredits()).isEqualTo(2000);
        assertThat(insertedTask.get().getRefundedAt()).isNotNull();

        insertedTask.get().setStatus("completed");
        service.completeSuccessfully(insertedTask.get());
        service.completeSuccessfully(insertedTask.get());
        assertThat(account.getCredits()).isEqualTo(1100);
        assertThat(insertedTask.get().getRefundedAt()).isNull();

        ArgumentCaptor<CreditLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(CreditLedgerEntity.class);
        verify(ledgers, times(3)).insert(ledgerCaptor.capture());
        List<CreditLedgerEntity> ledgerRows = ledgerCaptor.getAllValues();
        assertThat(ledgerRows.get(0).getBizType()).isEqualTo("video_generate");
        assertThat(ledgerRows.get(0).getChangeAmount()).isEqualTo(-900);
        assertThat(ledgerRows.get(0).getBalanceAfter()).isEqualTo(1100);
        assertThat(ledgerRows.get(1).getBizType()).isEqualTo("video_refund");
        assertThat(ledgerRows.get(1).getChangeAmount()).isEqualTo(900);
        assertThat(ledgerRows.get(1).getBalanceAfter()).isEqualTo(2000);
        assertThat(ledgerRows.get(2).getBizType()).isEqualTo("video_success_recovery");
        assertThat(ledgerRows.get(2).getChangeAmount()).isEqualTo(-900);
        assertThat(ledgerRows.get(2).getBalanceAfter()).isEqualTo(1100);
        assertThat(ledgerRows).allSatisfy(row -> assertThat(row.getBizId()).isEqualTo(created.id()));
        verify(users, times(3)).updateById(account);
    }
}
