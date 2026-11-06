package com.openimage.modules.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.StationMessageEntity;
import com.openimage.domain.entity.StationMessageReadEntity;
import com.openimage.domain.mapper.StationMessageMapper;
import com.openimage.domain.mapper.StationMessageReadMapper;
import com.openimage.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    private final StationMessageMapper messages;
    private final StationMessageReadMapper reads;

    public MessageController(StationMessageMapper messages, StationMessageReadMapper reads) {
        this.messages = messages;
        this.reads = reads;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal CurrentUser user) {
        OffsetDateTime now = OffsetDateTime.now();
        return ApiResponse.ok(messages.selectList(new LambdaQueryWrapper<StationMessageEntity>()
                .eq(StationMessageEntity::getDeleted, 0)
                .eq(StationMessageEntity::getEnabled, true)
                .and(wrapper -> wrapper.isNull(StationMessageEntity::getExpiresAt).or().gt(StationMessageEntity::getExpiresAt, now))
                .orderByDesc(StationMessageEntity::getPublishedAt))
            .stream()
            .map(message -> {
                boolean read = reads.selectCount(new LambdaQueryWrapper<StationMessageReadEntity>()
                    .eq(StationMessageReadEntity::getDeleted, 0)
                    .eq(StationMessageReadEntity::getMessageId, message.getId())
                    .eq(StationMessageReadEntity::getUserId, user.id())) > 0;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", message.getId());
                row.put("title", message.getTitle());
                row.put("content", message.getContent());
                row.put("published_at", message.getPublishedAt());
                row.put("expires_at", message.getExpiresAt());
                row.put("read", read);
                return row;
            })
            .toList());
    }

    @PatchMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> markRead(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        StationMessageEntity message = messages.selectById(id);
        if (message == null || message.getDeleted() != 0 || !Boolean.TRUE.equals(message.getEnabled())) {
            throw new IllegalArgumentException("Message not found");
        }
        StationMessageReadEntity read = new StationMessageReadEntity();
        read.setId("smr_" + UUID.randomUUID().toString().replace("-", ""));
        read.setMessageId(id);
        read.setUserId(user.id());
        read.setReadAt(OffsetDateTime.now());
        read.setDeleted(0);
        try {
            reads.insert(read);
        } catch (DuplicateKeyException ignored) {
            // already read
        }
        return ApiResponse.ok(Map.of("message_id", id, "read", true));
    }
}
