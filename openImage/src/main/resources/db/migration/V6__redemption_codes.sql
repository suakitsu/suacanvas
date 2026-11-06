ALTER TABLE users ALTER COLUMN credits SET DEFAULT 0;

CREATE TABLE IF NOT EXISTS redemption_codes (
  id VARCHAR(64) PRIMARY KEY,
  code VARCHAR(64) NOT NULL UNIQUE,
  credits INTEGER NOT NULL,
  max_uses INTEGER NOT NULL DEFAULT 1,
  used_count INTEGER NOT NULL DEFAULT 0,
  expires_at TIMESTAMPTZ,
  enabled BOOLEAN NOT NULL DEFAULT true,
  note VARCHAR(255),
  created_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS redemption_records (
  id VARCHAR(64) PRIMARY KEY,
  code_id VARCHAR(64) NOT NULL REFERENCES redemption_codes(id),
  code VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  credits INTEGER NOT NULL,
  redeemed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_redemption_records_code_user UNIQUE (code_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_redemption_codes_code ON redemption_codes(code);
CREATE INDEX IF NOT EXISTS idx_redemption_records_user ON redemption_records(user_id);
