package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.ImageTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Mapper
public interface ImageTaskMapper extends BaseMapper<ImageTaskEntity> {
    @Select("SELECT * FROM image_tasks WHERE id = #{id} FOR UPDATE")
    ImageTaskEntity lockById(@Param("id") String id);

    @Update("UPDATE image_tasks SET status = 'running', updated_at = now() WHERE id = #{id} AND status = 'queued' AND deleted = 0")
    int claimQueued(@Param("id") String id);

    @Update("""
        UPDATE image_tasks
        SET result_url = #{resultUrl},
            status = 'success',
            safe_error = '',
            raw_error = '',
            provider = #{provider},
            model = #{model},
            actual_cost = #{actualCost},
            provider_attempts = CAST(#{providerAttempts} AS jsonb),
            updated_at = now()
        WHERE id = #{id}
          AND status = 'running'
          AND deleted = 0
        """)
    int markSuccessIfRunning(
        @Param("id") String id,
        @Param("resultUrl") String resultUrl,
        @Param("provider") String provider,
        @Param("model") String model,
        @Param("actualCost") BigDecimal actualCost,
        @Param("providerAttempts") String providerAttempts
    );

    @Update("""
        UPDATE image_tasks
        SET status = 'failed',
            safe_error = #{safeError},
            raw_error = #{rawError},
            provider_attempts = CAST(#{providerAttempts} AS jsonb),
            updated_at = now()
        WHERE id = #{id}
          AND status = 'running'
          AND deleted = 0
        """)
    int markFailedIfRunning(
        @Param("id") String id,
        @Param("safeError") String safeError,
        @Param("rawError") String rawError,
        @Param("providerAttempts") String providerAttempts
    );

    @Update("UPDATE image_tasks SET status = 'queued', updated_at = now() WHERE id = #{id} AND status = 'running' AND deleted = 0")
    int requeueRunning(@Param("id") String id);

    @Update("""
        UPDATE image_tasks
        SET status = 'queued',
            provider_attempts = CAST(#{providerAttempts} AS jsonb),
            updated_at = now()
        WHERE id = #{id}
          AND status = 'running'
          AND deleted = 0
        """)
    int requeueRunningWithAttempts(
        @Param("id") String id,
        @Param("providerAttempts") String providerAttempts
    );

    @Update("""
        UPDATE image_tasks
        SET hidden_by_user = 1,
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND user_id = #{userId}
          AND project_id = #{projectId}
          AND status = 'failed'
          AND deleted = 0
          AND hidden_by_user = 0
        """)
    int hideFailedForUser(
        @Param("id") String id,
        @Param("userId") String userId,
        @Param("projectId") String projectId,
        @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Update("""
        UPDATE image_tasks
        SET deleted = 1,
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND deleted = 0
          AND status IN ('success', 'failed')
        """)
    int softDeleteTerminalByAdmin(@Param("id") String id, @Param("updatedAt") OffsetDateTime updatedAt);

    @Update("""
        UPDATE image_tasks
        SET hidden_by_user = 1,
            updated_at = #{updatedAt}
        WHERE user_id = #{userId}
          AND project_id = #{projectId}
          AND deleted = 0
          AND hidden_by_user = 0
        """)
    int hideByProjectForUser(
        @Param("userId") String userId,
        @Param("projectId") String projectId,
        @Param("updatedAt") OffsetDateTime updatedAt
    );
}
