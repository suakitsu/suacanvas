ALTER TABLE users
  ADD COLUMN IF NOT EXISTS register_ip VARCHAR(80),
  ADD COLUMN IF NOT EXISTS last_request_ip VARCHAR(80),
  ADD COLUMN IF NOT EXISTS last_request_at TIMESTAMPTZ;

ALTER TABLE redemption_codes
  ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS batch_name VARCHAR(120),
  ADD COLUMN IF NOT EXISTS batch_exclusive BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE redemption_records
  ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_users_register_ip ON users(register_ip);
CREATE INDEX IF NOT EXISTS idx_users_last_request_ip ON users(last_request_ip);
CREATE INDEX IF NOT EXISTS idx_redemption_codes_batch_id ON redemption_codes(batch_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_redemption_records_batch_user ON redemption_records(batch_id, user_id) WHERE deleted = 0 AND batch_id IS NOT NULL;
