package com.openimage.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.AdminAuditLogEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.AdminAuditLogMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoTaskAdminControllerTest {
    @Test
    void listsPagedTasksWithAdminDiagnostics() {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        UserMapper users = mock(UserMapper.class);
        VideoTaskEntity task = new VideoTaskEntity();
        task.setId("video-1");
        task.setUserId("user-1");
        task.setTitle("商品视频");
        task.setMode("standard");
        task.setStatus("completed");
        task.setProgress(100);
        task.setProvider("seedance");
        task.setModel("doubao-seedance-2.0");
        task.setRoutingReason("user_selected");
        task.setAspectRatio("9:16");
        task.setDurationSeconds(10);
        task.setRequestedResolution("720p");
        task.setActualWidth(704);
        task.setActualHeight(1280);
        task.setActualSeconds(new BigDecimal("10.04"));
        task.setCreditsCost(900);
        task.setActualCost(new BigDecimal("3.585701"));
        task.setMetadata("""
            {
              "upstream_cost": "5.28",
              "upstream_cost_unit": "APIMART_CREDIT",
              "cost_conversion_rate": "0.679110",
              "cost_source": "upstream_credits_cost"
            }
            """);
        task.setCreatedAt(OffsetDateTime.now());
        task.setUpdatedAt(OffsetDateTime.now());
        task.setHiddenByUser(1);
        task.setDeleted(0);

        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("user@example.com");
        when(tasks.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        when(users.selectBatchIds(any())).thenReturn(List.of(user));

        ApiResponse<Map<String, Object>> response = new VideoTaskAdminController(
            tasks, users, mock(AdminAuditLogMapper.class), new ObjectMapper().findAndRegisterModules()
        )
            .list("", "", "", 1, 20);

        assertThat(response.success()).isTrue();
        assertThat(response.data().get("total")).isEqualTo(1L);
        assertThat(response.data().get("total_pages")).isEqualTo(1L);
        List<?> rows = (List<?>) response.data().get("rows");
        assertThat(rows).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertThat(row.get("user_account")).isEqualTo("user@example.com");
        assertThat(row.get("provider")).isEqualTo("seedance");
        assertThat(row.get("actual_width")).isEqualTo(704);
        assertThat(row.get("actual_height")).isEqualTo(1280);
        assertThat(row.get("hidden_by_user")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> costDetails = (Map<String, Object>) row.get("cost_details");
        assertThat(costDetails)
            .containsEntry("currency", "CNY")
            .containsEntry("actual_cost_cny", new BigDecimal("3.585701"))
            .containsEntry("upstream_cost", "5.28")
            .containsEntry("upstream_cost_unit", "APIMART_CREDIT")
            .containsEntry("conversion_rate", "0.679110")
            .containsEntry("source", "upstream_credits_cost");
    }

    @Test
    void administratorCanDeleteTerminalVideoTaskWithAudit() throws Exception {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        AdminAuditLogMapper auditLogs = mock(AdminAuditLogMapper.class);
        VideoTaskEntity task = new VideoTaskEntity();
        task.setId("video-1");
        task.setUserId("user-1");
        task.setStatus("completed");
        task.setHiddenByUser(1);
        task.setDeleted(0);
        when(tasks.lockById("video-1")).thenReturn(task);
        when(tasks.softDeleteTerminalByAdmin(eq("video-1"), any(OffsetDateTime.class))).thenReturn(1);

        VideoTaskAdminController controller = new VideoTaskAdminController(
            tasks, mock(UserMapper.class), auditLogs, new ObjectMapper().findAndRegisterModules()
        );
        ApiResponse<Map<String, Object>> response = controller.delete(
            new CurrentUser("admin-1", "admin@example.com", "admin"),
            "video-1"
        );

        assertThat(response.data()).containsEntry("deleted", true);
        verify(tasks).softDeleteTerminalByAdmin(eq("video-1"), any(OffsetDateTime.class));
        ArgumentCaptor<AdminAuditLogEntity> audit = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditLogs).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("video_task_delete");
        assertThat(audit.getValue().getTargetId()).isEqualTo("video-1");
    }
}
