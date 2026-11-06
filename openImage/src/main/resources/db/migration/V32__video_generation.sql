CREATE TABLE IF NOT EXISTS video_tasks (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  title VARCHAR(255) NOT NULL DEFAULT '',
  prompt TEXT NOT NULL,
  source_image_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
  aspect_ratio VARCHAR(16) NOT NULL DEFAULT '9:16',
  duration_seconds INTEGER NOT NULL DEFAULT 10,
  requested_resolution VARCHAR(16) NOT NULL DEFAULT '720p',
  mode VARCHAR(32) NOT NULL DEFAULT 'standard',
  status VARCHAR(32) NOT NULL DEFAULT 'queued',
  progress INTEGER NOT NULL DEFAULT 0,
  provider VARCHAR(64) NOT NULL DEFAULT '',
  model VARCHAR(128) NOT NULL DEFAULT '',
  upstream_task_id VARCHAR(255),
  result_url TEXT,
  credits_cost INTEGER NOT NULL DEFAULT 0,
  actual_cost NUMERIC(12,6),
  actual_width INTEGER,
  actual_height INTEGER,
  actual_seconds NUMERIC(8,3),
  provider_attempts JSONB NOT NULL DEFAULT '[]'::jsonb,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  safe_error TEXT,
  raw_error TEXT,
  refunded_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_video_tasks_user_updated_active
ON video_tasks(user_id, updated_at DESC)
WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_video_tasks_status_updated_active
ON video_tasks(status, updated_at)
WHERE deleted = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_video_tasks_upstream_active
ON video_tasks(upstream_task_id)
WHERE upstream_task_id IS NOT NULL AND upstream_task_id <> '' AND deleted = 0;
