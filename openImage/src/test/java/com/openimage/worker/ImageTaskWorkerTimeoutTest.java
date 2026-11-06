package com.openimage.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageClient;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.domain.mapper.ProductImageMapper;
import com.openimage.domain.mapper.ProductProjectMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.storage.LocalStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageTaskWorkerTimeoutTest {
    @Mock private ImageTaskMapper tasks;
    @Mock private ProductProjectMapper projects;
    @Mock private ProductImageMapper productImages;
    @Mock private UserMapper users;
    @Mock private CreditLedgerMapper ledgers;
    @Mock private LocalStorageService storage;
    @Mock private AiImageClient aiImageClient;
    @Mock private TransactionTemplate transactionTemplate;

    private ImageTaskWorker worker;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        worker = new ImageTaskWorker(
            tasks,
            projects,
            productImages,
            users,
            ledgers,
            storage,
            aiImageClient,
            transactionTemplate,
            objectMapper,
            new DomesticCommerceOverlayRenderer(objectMapper),
            1
        );
    }

    @Test
    void failsTimedOutRunningTaskAndRefundsCredits() {
        OffsetDateTime staleUpdatedAt = OffsetDateTime.now().minusHours(2);
        ImageTaskEntity task = task(staleUpdatedAt, null, 3);
        UserEntity user = user(10);

        when(tasks.selectList(any())).thenReturn(List.of(task), List.of(task));
        when(tasks.lockById(task.getId())).thenReturn(task);
        when(users.lockById(task.getUserId())).thenReturn(user);
        when(ledgers.selectOne(any())).thenReturn(null);
        when(projects.selectById(task.getProjectId())).thenReturn(project());

        int changed = worker.failTimedOutRunningTasks();

        assertEquals(1, changed);
        assertEquals("failed", task.getStatus());
        assertEquals("任务超时，已退回本张图片积分，可点击重新生成", task.getSafeError());
        assertEquals("任务超过 1 小时仍处于 running 状态，系统判定为超时失败并自动退款", task.getRawError());
        assertNotNull(task.getRefundedAt());
        assertEquals(13, user.getCredits());

        ArgumentCaptor<CreditLedgerEntity> ledger = ArgumentCaptor.forClass(CreditLedgerEntity.class);
        verify(ledgers).insert(ledger.capture());
        assertEquals("image_refund", ledger.getValue().getBizType());
        assertTrue(ledger.getValue().getBizId().startsWith(task.getId() + ":timeout:"));
        assertEquals("图片生成超时自动退回积分：main", ledger.getValue().getRemark());
        assertEquals(3, ledger.getValue().getChangeAmount());
        verify(tasks).updateById(task);
    }

    @Test
    void doesNotRefundAgainWhenTaskAlreadyHasRefundedAt() {
        OffsetDateTime refundedAt = OffsetDateTime.now().minusMinutes(5);
        ImageTaskEntity task = task(OffsetDateTime.now().minusHours(2), refundedAt, 3);

        when(tasks.selectList(any())).thenReturn(List.of(task), List.of(task));
        when(tasks.lockById(task.getId())).thenReturn(task);
        when(projects.selectById(task.getProjectId())).thenReturn(project());

        int changed = worker.failTimedOutRunningTasks();

        assertEquals(1, changed);
        assertEquals("failed", task.getStatus());
        assertEquals(refundedAt, task.getRefundedAt());
        verify(users, never()).lockById(any());
        verify(ledgers, never()).insert(any(CreditLedgerEntity.class));
    }

    private static ImageTaskEntity task(OffsetDateTime updatedAt, OffsetDateTime refundedAt, int creditsCost) {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setId("img_timeout");
        task.setProjectId("proj_1");
        task.setUserId("user_1");
        task.setImageType("main");
        task.setStatus("running");
        task.setPromptSnapshot("{}");
        task.setCreditsCost(creditsCost);
        task.setRefundedAt(refundedAt);
        task.setCreatedAt(updatedAt.minusMinutes(10));
        task.setUpdatedAt(updatedAt);
        task.setDeleted(0);
        return task;
    }

    private static ProductProjectEntity project() {
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("proj_1");
        project.setUserId("user_1");
        project.setProductName("Test Product");
        project.setStatus("images_queued");
        project.setDeleted(0);
        return project;
    }

    private static UserEntity user(int credits) {
        UserEntity user = new UserEntity();
        user.setId("user_1");
        user.setCredits(credits);
        user.setDeleted(0);
        return user;
    }
}
