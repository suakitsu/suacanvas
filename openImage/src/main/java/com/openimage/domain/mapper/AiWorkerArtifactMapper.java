package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.AiWorkerArtifactEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiWorkerArtifactMapper extends BaseMapper<AiWorkerArtifactEntity> {
    @Select("""
        SELECT *
        FROM ai_worker_artifacts
        WHERE artifact_id = #{artifactId}
          AND job_id = #{jobId}
          AND deleted = 0
        LIMIT 1
        """)
    AiWorkerArtifactEntity findForJob(
        @Param("artifactId") String artifactId,
        @Param("jobId") String jobId
    );

    @Select("""
        SELECT *
        FROM ai_worker_artifacts
        WHERE job_id = #{jobId}
          AND idempotency_key = #{idempotencyKey}
          AND deleted = 0
        LIMIT 1
        """)
    AiWorkerArtifactEntity findByIdempotencyKey(
        @Param("jobId") String jobId,
        @Param("idempotencyKey") String idempotencyKey
    );
}
