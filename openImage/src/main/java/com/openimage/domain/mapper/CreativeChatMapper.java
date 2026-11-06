package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.CreativeChatEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface CreativeChatMapper extends BaseMapper<CreativeChatEntity> {
    @Update("""
        UPDATE creative_chats
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
}
