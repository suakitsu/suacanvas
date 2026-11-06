ALTER TABLE ai_worker_jobs
  ADD COLUMN IF NOT EXISTS lease_grace_until TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS completion_idempotency_key VARCHAR(128),
  ADD COLUMN IF NOT EXISTS completion_request_hash VARCHAR(64);

UPDATE ai_worker_jobs
SET lease_grace_until = lease_until
WHERE lease_until IS NOT NULL
  AND lease_grace_until IS NULL;

ALTER TABLE ai_worker_artifacts
  ADD COLUMN IF NOT EXISTS worker_id VARCHAR(120),
  ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128),
  ADD COLUMN IF NOT EXISTS source_sha256 VARCHAR(64);

UPDATE ai_worker_artifacts artifact
SET worker_id = job.worker_id
FROM ai_worker_jobs job
WHERE artifact.job_id = job.job_id
  AND artifact.worker_id IS NULL
  AND job.worker_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_worker_artifacts_job_idempotency
ON ai_worker_artifacts(job_id, idempotency_key)
WHERE deleted = 0
  AND idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_worker_jobs_grace
ON ai_worker_jobs(provider_id, lease_grace_until)
WHERE status = 'leased';
