package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.AiWorkerTokenEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiWorkerTokenMapper extends BaseMapper<AiWorkerTokenEntity> {
    @Select("""
        SELECT *
        FROM ai_worker_tokens
        WHERE token_hash = #{tokenHash}
          AND enabled = TRUE
          AND revoked_at IS NULL
          AND deleted = 0
        LIMIT 1
        """)
    AiWorkerTokenEntity findActiveByHash(@Param("tokenHash") String tokenHash);

    @Update("UPDATE ai_worker_tokens SET last_used_at = now(), updated_at = now() WHERE id = #{id} AND deleted = 0")
    int touch(@Param("id") String id);
}
