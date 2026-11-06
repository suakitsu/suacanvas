CREATE TABLE IF NOT EXISTS ai_worker_tokens (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  token_prefix VARCHAR(24) NOT NULL,
  token_hash VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  last_used_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_worker_tokens_hash_active
ON ai_worker_tokens(token_hash)
WHERE deleted = 0;

CREATE TABLE IF NOT EXISTS ai_worker_instances (
  worker_id VARCHAR(120) PRIMARY KEY,
  token_id VARCHAR(64) NOT NULL REFERENCES ai_worker_tokens(id),
  capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
  max_concurrency INTEGER NOT NULL DEFAULT 1,
  active_tasks INTEGER NOT NULL DEFAULT 0,
  last_heartbeat_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ai_worker_instances_heartbeat
ON ai_worker_instances(last_heartbeat_at DESC)
WHERE deleted = 0;

CREATE TABLE IF NOT EXISTS ai_worker_jobs (
  job_id VARCHAR(64) PRIMARY KEY,
  business_task_id VARCHAR(160) NOT NULL,
  provider_id VARCHAR(96) NOT NULL,
  capability VARCHAR(16) NOT NULL,
  protocol VARCHAR(48) NOT NULL,
  model VARCHAR(160) NOT NULL,
  request_json JSONB NOT NULL,
  input_files_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  request_hash VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  worker_id VARCHAR(120),
  lease_token VARCHAR(128),
  lease_until TIMESTAMPTZ,
  attempt INTEGER NOT NULL DEFAULT 0,
  result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  error_code VARCHAR(80),
  error_message VARCHAR(500),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  CONSTRAINT ck_ai_worker_jobs_capability CHECK (capability IN ('image', 'text')),
  CONSTRAINT ck_ai_worker_jobs_protocol CHECK (
    protocol IN (
      'openai_responses',
      'openai_chat_completions',
      'openai_images_generations',
      'openai_images_edits'
    )
  ),
  CONSTRAINT ck_ai_worker_jobs_status CHECK (
    status IN ('queued', 'leased', 'succeeded', 'failed', 'cancelled', 'expired')
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_worker_jobs_business_request
ON ai_worker_jobs(business_task_id, provider_id, capability, request_hash);

CREATE INDEX IF NOT EXISTS idx_ai_worker_jobs_claim
ON ai_worker_jobs(provider_id, capability, created_at ASC)
WHERE status = 'queued';

CREATE INDEX IF NOT EXISTS idx_ai_worker_jobs_active_lease
ON ai_worker_jobs(provider_id, lease_until)
WHERE status = 'leased';

CREATE INDEX IF NOT EXISTS idx_ai_worker_jobs_business
ON ai_worker_jobs(business_task_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS ai_worker_artifacts (
  artifact_id VARCHAR(64) PRIMARY KEY,
  job_id VARCHAR(64) NOT NULL REFERENCES ai_worker_jobs(job_id),
  storage_path VARCHAR(1000) NOT NULL,
  url VARCHAR(1000) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(120) NOT NULL,
  file_size BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  width INTEGER,
  height INTEGER,
  format VARCHAR(24),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ai_worker_artifacts_job
ON ai_worker_artifacts(job_id, created_at ASC)
WHERE deleted = 0;
