ALTER TABLE video_tasks
  ADD COLUMN IF NOT EXISTS routing_reason VARCHAR(128) NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS queued_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

UPDATE video_tasks
SET queued_at = COALESCE(queued_at, created_at)
WHERE queued_at IS NULL;

UPDATE video_tasks
SET submitted_at = COALESCE(submitted_at, created_at)
WHERE submitted_at IS NULL
  AND status IN ('submitted', 'generating', 'upscaling', 'quality_check', 'completed', 'failed', 'cancelled', 'timeout');

UPDATE video_tasks
SET completed_at = COALESCE(completed_at, updated_at)
WHERE completed_at IS NULL
  AND status IN ('completed', 'failed', 'cancelled', 'timeout');

ALTER TABLE video_tasks
  ALTER COLUMN queued_at SET DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_video_tasks_agnes_queue
ON video_tasks(provider, status, created_at, id)
WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_video_tasks_video_metrics
ON video_tasks(created_at, provider, model, status)
WHERE deleted = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_ledger_video_biz_active
ON credit_ledger(user_id, biz_type, biz_id)
WHERE deleted = 0
  AND biz_id IS NOT NULL
  AND biz_type IN ('video_generate', 'video_refund');
