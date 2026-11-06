CREATE INDEX IF NOT EXISTS idx_image_tasks_status_updated_active
ON image_tasks(status, updated_at)
WHERE deleted = 0;
