CREATE TABLE IF NOT EXISTS email_verification_codes (
  id VARCHAR(64) PRIMARY KEY,
  email_hash CHAR(64) NOT NULL,
  email_masked VARCHAR(255) NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  code_hash VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  provider VARCHAR(64) NOT NULL DEFAULT 'tencent_ses',
  provider_message_id VARCHAR(255),
  failure_reason VARCHAR(255),
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  sent_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_email_codes_email_sent
  ON email_verification_codes(email_hash, sent_at DESC)
  WHERE deleted = 0 AND sent_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_email_codes_lookup
  ON email_verification_codes(email_hash, purpose, created_at DESC)
  WHERE deleted = 0;
