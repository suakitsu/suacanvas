package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.CreativeChatVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface CreativeChatVersionMapper extends BaseMapper<CreativeChatVersionEntity> {
    @Update("""
        UPDATE creative_chat_versions
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
        UPDATE creative_chat_versions
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
