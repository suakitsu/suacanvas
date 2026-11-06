package com.openimage.modules.admin;

import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.AiTextCallLogEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.AiTextCallLogMapper;
import com.openimage.domain.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminTextCallControllerTest {
    @Test
    void listsAttributableCallsWithoutPromptContent() {
        AiTextCallLogMapper logs = mock(AiTextCallLogMapper.class);
        UserMapper users = mock(UserMapper.class);
        AdminTextCallController controller = new AdminTextCallController(logs, users);

        when(logs.selectCount(any())).thenReturn(1L);
        when(logs.selectList(any())).thenReturn(List.of(callLog()));
        when(logs.selectMaps(any())).thenReturn(List.of(Map.of(
            "calls", 1L,
            "users", 1L,
            "success", 1L,
            "failed", 0L,
            "queued", 0L,
            "avg_duration_ms", 820L
        )));
        when(users.selectList(any())).thenReturn(List.of(user()));

        ApiResponse<Map<String, Object>> response = controller.list(
            null, null, null, null, null, null, null, null, 1, 20
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.data().get("rows");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("user_email", "member@example.com");
            assertThat(row).containsEntry("provider", "local-cliproxy-text");
            assertThat(row).containsEntry("model", "gpt-5.5");
            assertThat(row).doesNotContainKeys("prompt", "messages", "error_message", "target_id");
        });

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) response.data().get("summary");
        assertThat(summary).containsEntry("calls", 1L).containsEntry("users", 1L);
    }

    private AiTextCallLogEntity callLog() {
        AiTextCallLogEntity row = new AiTextCallLogEntity();
        row.setId("txtlog_1");
        row.setUserId("u_1");
        row.setFeature("image_agent_prompt");
        row.setTargetType("creative_chat");
        row.setTargetId("private-input");
        row.setProvider("local-cliproxy-text");
        row.setModel("gpt-5.5");
        row.setStatus("success");
        row.setDurationMs(820);
        row.setInputChars(320);
        row.setOutputChars(180);
        row.setCreatedAt(OffsetDateTime.now());
        row.setDeleted(0);
        return row;
    }

    private UserEntity user() {
        UserEntity row = new UserEntity();
        row.setId("u_1");
        row.setEmail("member@example.com");
        row.setNickname("Member");
        row.setDeleted(0);
        return row;
    }
}
