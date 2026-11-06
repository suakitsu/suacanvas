CREATE TABLE IF NOT EXISTS workflow_templates (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500) NOT NULL DEFAULT '',
  graph_json JSONB NOT NULL,
  node_count INTEGER NOT NULL DEFAULT 1,
  edge_count INTEGER NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'active',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_workflow_templates_node_count CHECK (node_count BETWEEN 1 AND 60),
  CONSTRAINT ck_workflow_templates_edge_count CHECK (edge_count BETWEEN 0 AND 120),
  CONSTRAINT ck_workflow_templates_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX IF NOT EXISTS idx_workflow_templates_user_updated
ON workflow_templates(user_id, updated_at DESC)
WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_workflow_templates_admin_updated
ON workflow_templates(updated_at DESC)
WHERE deleted = 0;
