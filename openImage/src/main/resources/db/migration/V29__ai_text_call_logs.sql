CREATE TABLE IF NOT EXISTS ai_text_call_logs (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64),
  feature VARCHAR(80) NOT NULL,
  target_type VARCHAR(80),
  target_id VARCHAR(80),
  provider VARCHAR(80),
  model VARCHAR(160),
  status VARCHAR(32) NOT NULL,
  http_status INTEGER,
  duration_ms INTEGER,
  input_chars INTEGER,
  output_chars INTEGER,
  error_type VARCHAR(80),
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ai_text_call_logs_user_time
  ON ai_text_call_logs(user_id, created_at DESC)
  WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_ai_text_call_logs_feature_time
  ON ai_text_call_logs(feature, created_at DESC)
  WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_ai_text_call_logs_provider_time
  ON ai_text_call_logs(provider, model, created_at DESC)
  WHERE deleted = 0;
