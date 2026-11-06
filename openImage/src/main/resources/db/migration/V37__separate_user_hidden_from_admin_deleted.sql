ALTER TABLE video_tasks
ADD COLUMN IF NOT EXISTS hidden_by_user SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE image_tasks
ADD COLUMN IF NOT EXISTS hidden_by_user SMALLINT NOT NULL DEFAULT 0;

-- Video tasks had no administrator delete endpoint before this migration.
UPDATE video_tasks
SET hidden_by_user = 1,
    deleted = 0
WHERE deleted = 1;

-- Preserve administrator deletions. All other historical deletions came from
-- user-side project/task cleanup and should remain visible to administrators.
UPDATE image_tasks task
SET hidden_by_user = 1,
    deleted = 0,
    status = CASE WHEN task.status IN ('queued', 'running') THEN 'failed' ELSE task.status END,
    safe_error = CASE
      WHEN task.status IN ('queued', 'running') THEN '任务已在用户隐藏历史记录时终止'
      ELSE task.safe_error
    END,
    raw_error = CASE WHEN task.status IN ('queued', 'running') THEN '' ELSE task.raw_error END,
    updated_at = now()
WHERE task.deleted = 1
  AND NOT EXISTS (
    SELECT 1
    FROM admin_audit_logs audit
    WHERE audit.deleted = 0
      AND audit.action = 'image_task_delete'
      AND audit.target_type = 'image_task'
      AND audit.target_id = task.id
  );

CREATE INDEX IF NOT EXISTS idx_video_tasks_user_visible_created
ON video_tasks(user_id, created_at DESC)
WHERE deleted = 0 AND hidden_by_user = 0;

CREATE INDEX IF NOT EXISTS idx_image_tasks_user_visible_project
ON image_tasks(user_id, project_id, created_at)
WHERE deleted = 0 AND hidden_by_user = 0;
