CREATE TABLE IF NOT EXISTS creative_chats (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  title VARCHAR(255) NOT NULL,
  style VARCHAR(120),
  size VARCHAR(32),
  language VARCHAR(16),
  reference_url TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS creative_chat_messages (
  id VARCHAR(64) PRIMARY KEY,
  chat_id VARCHAR(64) NOT NULL REFERENCES creative_chats(id),
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  role VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  project_id VARCHAR(64) REFERENCES product_projects(id),
  task_id VARCHAR(64) REFERENCES image_tasks(id),
  result_url TEXT,
  status VARCHAR(64),
  version_no INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS creative_chat_versions (
  id VARCHAR(64) PRIMARY KEY,
  chat_id VARCHAR(64) NOT NULL REFERENCES creative_chats(id),
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  version_no INTEGER NOT NULL,
  user_message_id VARCHAR(64) REFERENCES creative_chat_messages(id),
  assistant_message_id VARCHAR(64) REFERENCES creative_chat_messages(id),
  project_id VARCHAR(64) REFERENCES product_projects(id),
  task_id VARCHAR(64) REFERENCES image_tasks(id),
  user_request TEXT NOT NULL,
  final_prompt TEXT NOT NULL,
  style VARCHAR(120),
  size VARCHAR(32),
  reference_url TEXT,
  status VARCHAR(64) NOT NULL DEFAULT 'queued',
  result_url TEXT,
  safe_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_creative_chats_user_updated
  ON creative_chats(user_id, updated_at DESC)
  WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_creative_chat_messages_chat_created
  ON creative_chat_messages(chat_id, created_at ASC)
  WHERE deleted = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_creative_chat_versions_chat_version_active
  ON creative_chat_versions(chat_id, version_no)
  WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_creative_chat_versions_task
  ON creative_chat_versions(task_id)
  WHERE deleted = 0 AND task_id IS NOT NULL;
