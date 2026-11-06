CREATE TABLE IF NOT EXISTS product_images (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL REFERENCES product_projects(id),
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  image_type VARCHAR(64) NOT NULL,
  original_filename VARCHAR(255),
  url TEXT NOT NULL,
  storage_path TEXT NOT NULL,
  content_type VARCHAR(120),
  file_size BIGINT NOT NULL DEFAULT 0,
  width INTEGER,
  height INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_product_images_project ON product_images(project_id, deleted);
CREATE INDEX IF NOT EXISTS idx_product_images_user ON product_images(user_id, deleted);
