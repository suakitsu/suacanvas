CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_ledger_billable_biz_active
ON credit_ledger(user_id, biz_type, biz_id)
WHERE deleted = 0
  AND biz_id IS NOT NULL
  AND biz_type IN (
    'recharge',
    'redeem_code',
    'manual_adjust',
    'image_generate',
    'image_retry',
    'image_refund',
    'aplus_generate',
    'creative_generate'
  );

CREATE INDEX IF NOT EXISTS idx_image_tasks_status_created_active
ON image_tasks(status, created_at)
WHERE deleted = 0;
