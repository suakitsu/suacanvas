CREATE TABLE IF NOT EXISTS admin_audit_logs (
  id VARCHAR(64) PRIMARY KEY,
  admin_id VARCHAR(64),
  action VARCHAR(80) NOT NULL,
  target_type VARCHAR(80) NOT NULL,
  target_id VARCHAR(128),
  summary VARCHAR(500) NOT NULL,
  before_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  after_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_admin_time
ON admin_audit_logs(admin_id, created_at DESC)
WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_target_time
ON admin_audit_logs(target_type, target_id, created_at DESC)
WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_image_tasks_created_status
ON image_tasks(created_at, status)
WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_recharge_orders_status_updated
ON recharge_orders(status, updated_at)
WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_credit_ledger_created_biz
ON credit_ledger(created_at, biz_type)
WHERE deleted = 0;
