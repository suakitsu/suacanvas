UPDATE video_tasks
SET mode = 'quality', updated_at = now()
WHERE mode = 'fast';
