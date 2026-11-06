ALTER TABLE video_tasks
ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uk_video_tasks_user_idempotency_active
ON video_tasks(user_id, idempotency_key)
WHERE deleted = 0
  AND idempotency_key IS NOT NULL
  AND idempotency_key <> '';

CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_ledger_video_recovery_active
ON credit_ledger(user_id, biz_type, biz_id)
WHERE deleted = 0
  AND biz_id IS NOT NULL
  AND biz_type = 'video_success_recovery';
