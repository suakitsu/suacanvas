package com.openimage.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageClient;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.domain.mapper.ProductImageMapper;
import com.openimage.domain.mapper.ProductProjectMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.storage.LocalStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageTaskWorkerPullWorkerTest {
    @Test
    void deferredPullWorkerTaskIsRequeuedWithoutFailureOrRefund() throws Exception {
        ImageTaskMapper tasks = mock(ImageTaskMapper.class);
        ProductProjectMapper projects = mock(ProductProjectMapper.class);
        ProductImageMapper productImages = mock(ProductImageMapper.class);
        UserMapper users = mock(UserMapper.class);
        CreditLedgerMapper ledgers = mock(CreditLedgerMapper.class);
        AiImageClient imageClient = mock(AiImageClient.class);
        ImageTaskEntity task = task();
        ProductProjectEntity project = project();
        when(projects.selectById("project-1")).thenReturn(project);
        when(productImages.selectList(any())).thenReturn(List.of());
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(imageClient.generate(eq(project), eq(task), anyList())).thenThrow(
            new AiImageClient.AiImageDeferredException("awj-1", "queued", List.of())
        );
        when(tasks.requeueRunningWithAttempts("task-1", "[]")).thenReturn(1);

        ImageTaskWorker worker = new ImageTaskWorker(
            tasks,
            projects,
            productImages,
            users,
            ledgers,
            mock(LocalStorageService.class),
            imageClient,
            mock(TransactionTemplate.class),
            new ObjectMapper(),
            mock(DomesticCommerceOverlayRenderer.class),
            1
        );
        Method process = ImageTaskWorker.class.getDeclaredMethod("process", ImageTaskEntity.class);
        process.setAccessible(true);

        process.invoke(worker, task);

        verify(tasks).requeueRunningWithAttempts("task-1", "[]");
        verify(tasks, never()).markFailedIfRunning(
            any(),
            any(),
            any(),
            any()
        );
        verify(users, never()).lockById(any());
        verify(ledgers, never()).insert(any(CreditLedgerEntity.class));
    }

    private ImageTaskEntity task() {
        ImageTaskEntity row = new ImageTaskEntity();
        row.setId("task-1");
        row.setProjectId("project-1");
        row.setUserId("user-1");
        row.setImageType("creative");
        row.setStatus("queued");
        row.setPromptSnapshot("{}");
        row.setDeleted(0);
        return row;
    }

    private ProductProjectEntity project() {
        ProductProjectEntity row = new ProductProjectEntity();
        row.setId("project-1");
        row.setStatus("images_queued");
        row.setDeleted(0);
        return row;
    }
}
