package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.AiWorkerJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AiWorkerJobMapper extends BaseMapper<AiWorkerJobEntity> {
    @Select("SELECT * FROM ai_worker_jobs WHERE job_id = #{jobId} FOR UPDATE")
    AiWorkerJobEntity lockById(@Param("jobId") String jobId);

    @Select("""
        SELECT *
        FROM ai_worker_jobs
        WHERE business_task_id = #{businessTaskId}
          AND provider_id = #{providerId}
          AND capability = #{capability}
          AND request_hash = #{requestHash}
        LIMIT 1
        """)
    AiWorkerJobEntity findDedupe(
        @Param("businessTaskId") String businessTaskId,
        @Param("providerId") String providerId,
        @Param("capability") String capability,
        @Param("requestHash") String requestHash
    );

    @Select("""
        SELECT 1
        FROM (
          SELECT pg_advisory_xact_lock(hashtext('picpilot_ai_worker:' || #{providerId}))
        ) locked
        """)
    int lockProvider(@Param("providerId") String providerId);

    @Select("""
        SELECT 1
        FROM (
          SELECT pg_advisory_xact_lock(hashtext('picpilot_ai_worker_instance:' || #{workerId}))
        ) locked
        """)
    int lockWorker(@Param("workerId") String workerId);

    @Select("""
        SELECT COUNT(*)
        FROM ai_worker_jobs
        WHERE provider_id = #{providerId}
          AND status = 'leased'
          AND COALESCE(lease_grace_until, lease_until) > now()
        """)
    int countActiveByProvider(@Param("providerId") String providerId);

    @Select("""
        SELECT COUNT(*)
        FROM ai_worker_jobs
        WHERE worker_id = #{workerId}
          AND status = 'leased'
          AND COALESCE(lease_grace_until, lease_until) > now()
        """)
    int countActiveByWorker(@Param("workerId") String workerId);

    @Select("""
        WITH candidate AS (
          SELECT job_id
          FROM ai_worker_jobs
          WHERE provider_id = #{providerId}
            AND capability = #{capability}
            AND status = 'queued'
          ORDER BY created_at ASC
          FOR UPDATE SKIP LOCKED
          LIMIT 1
        )
        UPDATE ai_worker_jobs job
        SET status = 'leased',
            worker_id = #{workerId},
            lease_token = #{leaseTokenHash},
            lease_until = #{leaseUntil},
            lease_grace_until = #{leaseGraceUntil},
            attempt = attempt + 1,
            error_code = NULL,
            error_message = NULL,
            updated_at = now()
        FROM candidate
        WHERE job.job_id = candidate.job_id
        RETURNING job.*
        """)
    AiWorkerJobEntity claimNext(
        @Param("providerId") String providerId,
        @Param("capability") String capability,
        @Param("workerId") String workerId,
        @Param("leaseTokenHash") String leaseTokenHash,
        @Param("leaseUntil") OffsetDateTime leaseUntil,
        @Param("leaseGraceUntil") OffsetDateTime leaseGraceUntil
    );

    @Update("""
        UPDATE ai_worker_jobs
        SET status = 'expired',
            error_code = 'LEASE_EXPIRED',
            error_message = 'Worker lease expired',
            updated_at = now()
        WHERE status = 'leased'
          AND COALESCE(lease_grace_until, lease_until) <= now()
        """)
    int expireLeases();

    @Update("""
        UPDATE ai_worker_jobs
        SET status = 'queued',
            worker_id = NULL,
            lease_token = NULL,
            lease_until = NULL,
            lease_grace_until = NULL,
            error_code = NULL,
            error_message = NULL,
            updated_at = now()
        WHERE provider_id = #{providerId}
          AND status = 'expired'
        """)
    int requeueExpired(@Param("providerId") String providerId);

    @Update("""
        UPDATE ai_worker_jobs
        SET status = 'cancelled',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            updated_at = now(),
            completed_at = now()
        WHERE job_id = #{jobId}
          AND status = 'queued'
        """)
    int cancelQueued(
        @Param("jobId") String jobId,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage
    );

    @Update("""
        UPDATE ai_worker_jobs
        SET status = 'cancelled',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            updated_at = now(),
            completed_at = now()
        WHERE job_id = #{jobId}
          AND status IN ('queued', 'leased')
        """)
    int cancelActive(
        @Param("jobId") String jobId,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage
    );

    @Update("""
        UPDATE ai_worker_jobs job
        SET status = 'cancelled',
            error_code = 'BUSINESS_TASK_CANCELLED',
            error_message = 'Business image task is no longer active',
            updated_at = now(),
            completed_at = now()
        WHERE job.capability = 'image'
          AND job.status IN ('queued', 'leased')
          AND NOT EXISTS (
            SELECT 1
            FROM image_tasks task
            WHERE task.id = job.business_task_id
              AND task.deleted = 0
              AND task.status IN ('queued', 'running')
          )
        """)
    int cancelInactiveImageJobs();

    @Select("""
        SELECT *
        FROM ai_worker_jobs
        WHERE business_task_id = #{businessTaskId}
          AND status IN ('queued', 'leased')
        ORDER BY created_at ASC
        """)
    List<AiWorkerJobEntity> findBlockingByBusinessTask(@Param("businessTaskId") String businessTaskId);

    @Select("""
        SELECT COUNT(*)
        FROM ai_worker_jobs
        WHERE business_task_id = #{businessTaskId}
          AND status IN ('queued', 'leased')
        """)
    int countBlockingByBusinessTask(@Param("businessTaskId") String businessTaskId);
}
