ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS github_id VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS github_login VARCHAR(120);
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_provider VARCHAR(32) NOT NULL DEFAULT 'password';

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_github_id_active
ON users(github_id)
WHERE github_id IS NOT NULL AND deleted = 0;

ALTER TABLE redemption_codes ADD COLUMN IF NOT EXISTS benefit_type VARCHAR(64) NOT NULL DEFAULT '福利积分';
ALTER TABLE redemption_codes ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'active';
ALTER TABLE redemption_codes ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ;

ALTER TABLE redemption_records ADD COLUMN IF NOT EXISTS user_email_masked VARCHAR(255);
ALTER TABLE redemption_records ADD COLUMN IF NOT EXISTS user_nickname VARCHAR(100);

CREATE TABLE IF NOT EXISTS station_messages (
  id VARCHAR(64) PRIMARY KEY,
  title VARCHAR(160) NOT NULL,
  content TEXT NOT NULL,
  audience VARCHAR(32) NOT NULL DEFAULT 'all',
  published_by VARCHAR(64),
  published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ,
  enabled BOOLEAN NOT NULL DEFAULT true,
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS station_message_reads (
  id VARCHAR(64) PRIMARY KEY,
  message_id VARCHAR(64) NOT NULL REFERENCES station_messages(id),
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_station_message_reads_user UNIQUE (message_id, user_id)
);

ALTER TABLE image_tasks ADD COLUMN IF NOT EXISTS task_set_id VARCHAR(64);
ALTER TABLE image_tasks ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMPTZ;
UPDATE image_tasks SET task_set_id = project_id WHERE task_set_id IS NULL;

UPDATE redemption_codes
SET status = CASE
  WHEN deleted <> 0 THEN 'deleted'
  WHEN enabled = false THEN 'disabled'
  WHEN expires_at IS NOT NULL AND expires_at <= now() THEN 'expired'
  WHEN used_count >= max_uses THEN 'used_up'
  ELSE 'active'
END;
