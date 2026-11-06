package com.openimage.modules.admin;

import com.openimage.ai.AiImageClient;
import com.openimage.common.ApiResponse;
import com.openimage.common.BusinessTime;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ProductImageEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.AdminAuditLogMapper;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.domain.mapper.ImageTypeConfigMapper;
import com.openimage.domain.mapper.PaymentChannelMapper;
import com.openimage.domain.mapper.ProductImageMapper;
import com.openimage.domain.mapper.ProductProjectMapper;
import com.openimage.domain.mapper.PromptTemplateMapper;
import com.openimage.domain.mapper.RedemptionCodeMapper;
import com.openimage.domain.mapper.RedemptionRecordMapper;
import com.openimage.domain.mapper.RechargeOrderMapper;
import com.openimage.domain.mapper.RechargePlanMapper;
import com.openimage.domain.mapper.RiskEventMapper;
import com.openimage.domain.mapper.StationMessageMapper;
import com.openimage.domain.mapper.StationMessageReadMapper;
import com.openimage.domain.mapper.SystemSettingMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.modules.billing.BillingService;
import com.openimage.storage.LocalStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminControllerReportingTest {
    private ImageTaskMapper imageTasks;
    private UserMapper users;
    private ProductProjectMapper projects;
    private ProductImageMapper productImages;
    private VideoTaskMapper videoTasks;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        projects = mock(ProductProjectMapper.class);
        imageTasks = mock(ImageTaskMapper.class);
        productImages = mock(ProductImageMapper.class);
        videoTasks = mock(VideoTaskMapper.class);
        CreditLedgerMapper ledgers = mock(CreditLedgerMapper.class);
        RechargeOrderMapper orders = mock(RechargeOrderMapper.class);

        when(users.selectCount(any())).thenReturn(1L);
        when(projects.selectCount(any())).thenReturn(1L);
        when(orders.selectCount(any())).thenReturn(0L);
        when(users.selectList(any())).thenReturn(List.of(user()));
        when(orders.selectList(any())).thenReturn(List.of());
        when(ledgers.selectList(any())).thenReturn(List.of());

        controller = new AdminController(
            users,
            projects,
            imageTasks,
            productImages,
            videoTasks,
            ledgers,
            mock(RechargePlanMapper.class),
            mock(PaymentChannelMapper.class),
            orders,
            mock(ImageTypeConfigMapper.class),
            mock(PromptTemplateMapper.class),
            mock(RedemptionCodeMapper.class),
            mock(RedemptionRecordMapper.class),
            mock(RiskEventMapper.class),
            mock(StationMessageMapper.class),
            mock(StationMessageReadMapper.class),
            mock(SystemSettingMapper.class),
            mock(AdminAuditLogMapper.class),
            mock(BillingService.class),
            mock(LocalStorageService.class),
            mock(AiImageClient.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            mock(PasswordEncoder.class)
        );
    }

    @Test
    void dashboardIncludesImageAndVideoTasksInShanghaiTrend() {
        when(imageTasks.selectList(any())).thenReturn(List.of(imageTask()));
        when(videoTasks.selectList(any())).thenReturn(List.of(videoTask()));

        ApiResponse<Map<String, Object>> response = controller.dashboard(
            null, null, null, null, null, null, null, null
        );

        assertThat(response.data())
            .containsEntry("image_tasks", 1L)
            .containsEntry("video_tasks", 1L)
            .containsEntry("total_tasks", 2L)
            .containsEntry("failed_tasks", 0L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trends = (List<Map<String, Object>>) response.data().get("trends");
        Map<String, Object> today = trends.stream()
            .filter(row -> BusinessTime.today().toString().equals(row.get("date")))
            .findFirst()
            .orElseThrow();
        assertThat(today)
            .containsEntry("usage", 2)
            .containsEntry("image_usage", 1)
            .containsEntry("video_usage", 1);
    }

    @Test
    void costReportSeparatesImageAndVideoRows() {
        when(imageTasks.selectList(any())).thenReturn(List.of(imageTask()));
        when(videoTasks.selectList(any())).thenReturn(List.of(videoTask()));

        ApiResponse<Map<String, Object>> response = controller.costs(
            null, null, null, null, null, null, null, null
        );

        assertThat(response.data())
            .containsEntry("image_task_count", 1)
            .containsEntry("video_task_count", 1)
            .containsEntry("task_count", 2);
        assertThat((BigDecimal) response.data().get("image_cost")).isEqualByComparingTo("0.020000");
        assertThat((BigDecimal) response.data().get("video_cost")).isEqualByComparingTo("3.585701");

        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) response.data().get("cost_policy");
        assertThat(policy)
            .containsEntry("currency", "CNY")
            .containsEntry("rua_chat_per_image_cny", new BigDecimal("0.020000"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.data().get("rows");
        assertThat(rows).extracting(row -> row.get("media_type"))
            .containsExactlyInAnyOrder("image", "video");
    }

    @Test
    void costReportPrefersStoredApimartUpstreamConversionDetails() {
        ImageTaskEntity task = imageTask();
        task.setProvider("aishuch");
        task.setActualCost(new BigDecimal("0.126000"));
        task.setProviderAttempts("""
            [{
              "provider":"aishuch",
              "success":true,
              "actualCostCny":0.142613,
              "upstreamCost":0.21,
              "upstreamCostUnit":"APIMART_CREDIT",
              "conversionRate":0.679110,
              "costSource":"upstream_response"
            }]
            """);
        when(imageTasks.selectList(any())).thenReturn(List.of(task));
        when(videoTasks.selectList(any())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> response = controller.costs(
            null, null, null, null, null, null, null, null
        );

        assertThat((BigDecimal) response.data().get("image_cost")).isEqualByComparingTo("0.142613");
    }

    @Test
    void failedImageWithoutConfirmedUpstreamChargeHasZeroCost() {
        ImageTaskEntity task = imageTask();
        task.setStatus("failed");
        task.setProvider("aishuch");
        task.setActualCost(new BigDecimal("0.126000"));
        task.setProviderAttempts("[]");
        when(imageTasks.selectList(any())).thenReturn(List.of(task));
        when(videoTasks.selectList(any())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> response = controller.costs(
            null, null, null, null, null, null, null, null
        );

        assertThat((BigDecimal) response.data().get("image_cost")).isEqualByComparingTo("0");
    }

    @Test
    void historicalRuaTaskKeepsItsStoredCost() {
        ImageTaskEntity task = imageTask();
        task.setActualCost(new BigDecimal("0.013000"));
        when(imageTasks.selectList(any())).thenReturn(List.of(task));
        when(videoTasks.selectList(any())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> response = controller.costs(
            null, null, null, null, null, null, null, null
        );

        assertThat((BigDecimal) response.data().get("image_cost")).isEqualByComparingTo("0.013000");
    }

    @Test
    void imageTaskDetailIncludesFullEmailInputsAndPreviewMedia() {
        ImageTaskEntity task = imageTask();
        task.setPromptSnapshot("""
            {"taskKind":"creative","modelPrompt":"保持商品真实，生成雨天使用场景","resolution":"2k"}
            """);
        task.setResultUrl("/files/generated/image-1.png");
        task.setProviderAttempts("[]");
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("project-1");
        project.setProductName("朱砂红折叠雨伞");
        project.setOriginalInfo("{\"audience\":\"通勤人群\"}");
        project.setConfirmedFacts("{\"color\":\"朱砂红\"}");
        project.setDeleted(0);
        ProductImageEntity reference = new ProductImageEntity();
        reference.setId("reference-1");
        reference.setProjectId("project-1");
        reference.setUserId("user-1");
        reference.setImageType("reference");
        reference.setOriginalFilename("umbrella.heic");
        reference.setUrl("/files/references/umbrella.jpg");
        reference.setContentType("image/jpeg");
        reference.setImageFormat("jpeg");
        reference.setWidth(1200);
        reference.setHeight(1600);
        reference.setDeleted(0);
        when(imageTasks.selectById("image-1")).thenReturn(task);
        when(users.selectById("user-1")).thenReturn(user());
        when(projects.selectById("project-1")).thenReturn(project);
        when(productImages.selectList(any())).thenReturn(List.of(reference));

        ApiResponse<Map<String, Object>> response = controller.imageTaskDetail("image-1");

        assertThat(response.data())
            .containsEntry("user_email", "full-email@example.com")
            .containsEntry("result_url", "/files/generated/image-1.png")
            .containsKey("prompt_details");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> references = (List<Map<String, Object>>) response.data().get("reference_images");
        assertThat(references).hasSize(1);
        assertThat(references.get(0))
            .containsEntry("url", "/files/references/umbrella.jpg")
            .doesNotContainKey("storage_path");
    }

    private UserEntity user() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("full-email@example.com");
        user.setCredits(1000);
        user.setDeleted(0);
        user.setCreatedAt(OffsetDateTime.now());
        return user;
    }

    private ImageTaskEntity imageTask() {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setId("image-1");
        task.setUserId("user-1");
        task.setProjectId("project-1");
        task.setImageType("creative");
        task.setStatus("success");
        task.setProvider("rua.chat");
        task.setModel("gpt-image-2");
        task.setActualCost(new BigDecimal("0.020000"));
        task.setCreditsCost(20);
        task.setCreatedAt(OffsetDateTime.now());
        task.setDeleted(0);
        return task;
    }

    private VideoTaskEntity videoTask() {
        VideoTaskEntity task = new VideoTaskEntity();
        task.setId("video-1");
        task.setUserId("user-1");
        task.setStatus("completed");
        task.setProvider("seedance");
        task.setModel("doubao-seedance-2.0");
        task.setActualCost(new BigDecimal("3.585701"));
        task.setCreditsCost(570);
        task.setCreatedAt(OffsetDateTime.now());
        task.setDeleted(0);
        return task;
    }
}
