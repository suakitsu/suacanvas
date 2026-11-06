package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.CreativeChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface CreativeChatMessageMapper extends BaseMapper<CreativeChatMessageEntity> {
    @Update("""
        UPDATE creative_chat_messages
        SET deleted = 1,
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND user_id = #{userId}
          AND deleted = 0
        """)
    int softDeleteById(
        @Param("id") String id,
        @Param("userId") String userId,
        @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Update("""
        UPDATE creative_chat_messages
        SET deleted = 1,
            updated_at = #{updatedAt}
        WHERE chat_id = #{chatId}
          AND user_id = #{userId}
          AND deleted = 0
        """)
    int softDeleteByChat(
        @Param("chatId") String chatId,
        @Param("userId") String userId,
        @Param("updatedAt") OffsetDateTime updatedAt
    );
}
