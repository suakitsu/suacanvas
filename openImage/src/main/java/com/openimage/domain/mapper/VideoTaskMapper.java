package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.VideoTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTaskEntity> {
    @Select("SELECT * FROM video_tasks WHERE id = #{id} FOR UPDATE")
    VideoTaskEntity lockById(@Param("id") String id);

    @Select("""
        SELECT *
        FROM video_tasks
        WHERE user_id = #{userId}
          AND idempotency_key = #{idempotencyKey}
          AND deleted = 0
        LIMIT 1
        """)
    VideoTaskEntity findByUserAndIdempotencyKey(
        @Param("userId") String userId,
        @Param("idempotencyKey") String idempotencyKey
    );

    @Update("UPDATE video_tasks SET status = 'submitting', progress = 5, updated_at = now() WHERE id = #{id} AND status = 'queued' AND deleted = 0")
    int claimQueued(@Param("id") String id);

    @Select("""
        SELECT COUNT(*)
        FROM video_tasks
        WHERE deleted = 0
          AND provider = #{provider}
          AND status IN (
            'queued', 'submitting', 'submission_unknown',
            'submitted', 'generating', 'upscaling', 'quality_check'
          )
        """)
    int countActiveByProvider(@Param("provider") String provider);

    @Update("""
        UPDATE video_tasks
        SET hidden_by_user = 1,
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND user_id = #{userId}
          AND deleted = 0
          AND hidden_by_user = 0
          AND status IN ('completed', 'failed', 'timeout', 'cancelled')
        """)
    int hideTerminalOwned(
        @Param("id") String id,
        @Param("userId") String userId,
        @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Update("""
        UPDATE video_tasks
        SET deleted = 1,
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND deleted = 0
          AND status IN ('completed', 'failed', 'timeout', 'cancelled')
        """)
    int softDeleteTerminalByAdmin(
        @Param("id") String id,
        @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Update("""
        UPDATE video_tasks
        SET status = 'submitting',
            progress = 5,
            metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
              'agnes_key_id', #{keyId},
              'agnes_key_label', #{keyLabel},
              'agnes_key_fingerprint', #{keyFingerprint}
            ),
            updated_at = now()
        WHERE id = #{id}
          AND provider = 'agnes'
          AND status = 'queued'
          AND deleted = 0
        """)
    int claimQueuedAgnesWithKey(
        @Param("id") String id,
        @Param("keyId") String keyId,
        @Param("keyLabel") String keyLabel,
        @Param("keyFingerprint") String keyFingerprint
    );

    @Select("""
        SELECT *
        FROM video_tasks
        WHERE id = #{id}
          AND provider = 'agnes'
          AND status = 'submitting'
          AND deleted = 0
        LIMIT 1
        """)
    VideoTaskEntity findClaimedAgnesById(@Param("id") String id);

    @Update("""
        UPDATE video_tasks
        SET updated_at = now()
        WHERE id = #{id}
          AND deleted = 0
          AND status = #{status}
          AND updated_at = #{updatedAt}
        """)
    int claimInFlight(
        @Param("id") String id,
        @Param("status") String status,
        @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Select("""
        SELECT COUNT(*)
        FROM video_tasks
        WHERE deleted = 0
          AND provider = 'agnes'
          AND status IN ('submitting', 'submitted', 'generating', 'upscaling', 'quality_check')
        """)
    int countActiveAgnes();

    @Select("""
        SELECT
          COALESCE(metadata ->> 'agnes_key_id', '') AS key_id,
          COUNT(*) FILTER (
            WHERE provider = 'agnes'
              AND status IN ('submitting', 'submitted', 'generating', 'upscaling', 'quality_check')
          ) AS active_count,
          COUNT(*) FILTER (
            WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'
          ) AS today_count,
          COUNT(*) FILTER (
            WHERE provider = 'agnes'
              AND status = 'completed'
              AND created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'
          ) AS success_count,
          COUNT(*) FILTER (
            WHERE provider_attempts @> '[{"provider":"agnes","status":"failed"}]'::jsonb
              AND created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'
          ) AS failure_count
        FROM video_tasks
        WHERE deleted = 0
          AND (
            provider = 'agnes'
            OR provider_attempts @> '[{"provider":"agnes"}]'::jsonb
          )
          AND created_at >= now() - interval '30 days'
        GROUP BY COALESCE(metadata ->> 'agnes_key_id', '')
        """)
    List<Map<String, Object>> selectAgnesKeyMetrics();

    @Select("""
        SELECT COUNT(*)
        FROM video_tasks
        WHERE deleted = 0
          AND provider = 'agnes'
          AND metadata ->> 'agnes_key_id' = #{keyId}
          AND status IN ('submitting', 'submitted', 'generating', 'upscaling', 'quality_check')
        """)
    int countActiveAgnesByKey(@Param("keyId") String keyId);

    @Select("""
        SELECT *
        FROM video_tasks
        WHERE deleted = 0
          AND provider = 'agnes'
          AND status = 'queued'
          AND (
            metadata ->> 'submit_retry_after' IS NULL
            OR metadata ->> 'submit_retry_after' = ''
            OR (metadata ->> 'submit_retry_after')::timestamptz <= now()
          )
        ORDER BY created_at ASC, id ASC
        FOR UPDATE SKIP LOCKED
        LIMIT 1
        """)
    VideoTaskEntity lockNextQueuedAgnes();

    @Update("""
        UPDATE video_tasks
        SET status = 'queued',
            progress = 0,
            metadata = CAST(#{metadata} AS jsonb),
            upstream_task_id = NULL,
            updated_at = now()
        WHERE id = #{id}
          AND provider = 'agnes'
          AND status = 'submitting'
          AND (upstream_task_id IS NULL OR upstream_task_id = '')
          AND deleted = 0
        """)
    int requeueAgnesAfterKeyFailure(@Param("id") String id, @Param("metadata") String metadata);

    @Update("""
        UPDATE video_tasks
        SET status = 'queued', progress = 0, updated_at = now()
        WHERE deleted = 0
          AND status = 'submitting'
          AND (upstream_task_id IS NULL OR upstream_task_id = '')
          AND updated_at < #{staleBefore}
        """)
    int requeueStaleSubmitting(@Param("staleBefore") OffsetDateTime staleBefore);

    @Update("""
        UPDATE video_tasks
        SET status = 'submission_unknown',
            progress = 5,
            safe_error = '上游提交结果正在核验，请勿重复提交相同任务',
            metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
              'submission_unknown_at', now(),
              'submission_unknown_reason', 'worker_interrupted_during_submission',
              'automatic_resubmit_blocked', true,
              'route_locked', true
            ),
            updated_at = now()
        WHERE deleted = 0
          AND status = 'submitting'
          AND (upstream_task_id IS NULL OR upstream_task_id = '')
          AND updated_at < #{staleBefore}
        """)
    int quarantineStaleSubmitting(@Param("staleBefore") OffsetDateTime staleBefore);

    @Select("""
        SELECT
          (SELECT COUNT(*) FROM video_tasks active
           WHERE active.deleted = 0
             AND active.provider = 'agnes'
             AND active.status IN ('submitting', 'submitted', 'generating', 'upscaling', 'quality_check'))
          +
          (SELECT COUNT(*) FROM video_tasks waiting
           WHERE waiting.deleted = 0
             AND waiting.provider = 'agnes'
             AND waiting.status = 'queued'
             AND (waiting.created_at < #{createdAt}
                  OR (waiting.created_at = #{createdAt} AND waiting.id < #{id})))
        """)
    int countAgnesAhead(@Param("createdAt") OffsetDateTime createdAt, @Param("id") String id);

    @Select("""
        WITH attempt_groups AS (
          SELECT
            vt.id,
            vt.status AS task_status,
            vt.provider AS final_provider,
            vt.queued_at,
            vt.submitted_at,
            vt.completed_at,
            vt.actual_cost,
            item.value ->> 'provider' AS provider,
            item.value ->> 'model' AS model,
            bool_or((item.value ->> 'status') IN ('submitted', 'fallback_submitted')) AS called,
            bool_or((item.value ->> 'status') = 'failed') AS failed
          FROM video_tasks vt
          CROSS JOIN LATERAL jsonb_array_elements(vt.provider_attempts) item
          WHERE vt.deleted = 0
            AND vt.created_at >= now() - interval '30 days'
          GROUP BY vt.id, vt.status, vt.provider, vt.queued_at, vt.submitted_at, vt.completed_at, vt.actual_cost,
                   item.value ->> 'provider', item.value ->> 'model'
        )
        SELECT
          provider,
          model,
          COUNT(*) FILTER (WHERE called) AS call_count,
          COUNT(*) FILTER (WHERE task_status = 'completed' AND final_provider = provider) AS success_count,
          COUNT(*) FILTER (WHERE failed) AS failure_count,
          COALESCE(ROUND((AVG(EXTRACT(EPOCH FROM (submitted_at - queued_at)))
            FILTER (WHERE final_provider = provider AND submitted_at IS NOT NULL AND queued_at IS NOT NULL))::numeric, 1), 0) AS average_queue_seconds,
          COALESCE(ROUND((AVG(EXTRACT(EPOCH FROM (completed_at - submitted_at)))
            FILTER (WHERE final_provider = provider AND completed_at IS NOT NULL AND submitted_at IS NOT NULL))::numeric, 1), 0) AS average_generation_seconds,
          COALESCE(ROUND((AVG(actual_cost)
            FILTER (WHERE final_provider = provider AND called))::numeric, 6), 0) AS average_cost,
          COALESCE(SUM(actual_cost)
            FILTER (WHERE final_provider = provider AND called), 0) AS total_cost
        FROM attempt_groups
        WHERE called OR failed
        GROUP BY provider, model
        ORDER BY call_count DESC, provider ASC
        """)
    List<Map<String, Object>> selectVideoModelMetrics();

    @Select("""
        SELECT
          COUNT(*) FILTER (WHERE provider = 'agnes' AND status = 'queued') AS agnes_queued,
          COUNT(*) FILTER (WHERE provider = 'agnes' AND status IN ('submitting', 'submitted', 'generating', 'upscaling', 'quality_check')) AS agnes_active,
          COUNT(*) FILTER (WHERE provider_attempts @> '[{"provider":"gemini","status":"fallback_submitted"}]'::jsonb) AS agnes_fallback_gemini,
          COALESCE(ROUND((AVG(EXTRACT(EPOCH FROM (submitted_at - queued_at)))
            FILTER (WHERE submitted_at IS NOT NULL AND queued_at IS NOT NULL))::numeric, 1), 0) AS average_queue_seconds,
          COALESCE(ROUND((AVG(EXTRACT(EPOCH FROM (completed_at - submitted_at)))
            FILTER (WHERE completed_at IS NOT NULL AND submitted_at IS NOT NULL))::numeric, 1), 0) AS average_generation_seconds,
          COALESCE(SUM(actual_cost) FILTER (
            WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'
          ), 0) AS daily_total_cost,
          COALESCE(SUM(credits_cost) FILTER (WHERE refunded_at IS NULL), 0) AS credits_income,
          COALESCE(SUM(actual_cost), 0) AS upstream_total_cost,
          COALESCE(SUM(credits_cost) FILTER (WHERE refunded_at IS NULL), 0) - COALESCE(SUM(actual_cost), 0) AS income_cost_difference
        FROM video_tasks
        WHERE deleted = 0
          AND created_at >= now() - interval '30 days'
        """)
    Map<String, Object> selectVideoSummaryMetrics();

    @Select("SELECT * FROM video_tasks WHERE upstream_task_id = #{upstreamTaskId} AND deleted = 0 LIMIT 1")
    VideoTaskEntity findByUpstreamTaskId(@Param("upstreamTaskId") String upstreamTaskId);
}
