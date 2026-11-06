CREATE UNIQUE INDEX IF NOT EXISTS uk_image_tasks_project_type_active
ON image_tasks(project_id, image_type)
WHERE deleted = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_ledger_image_generate_active
ON credit_ledger(user_id, biz_type, biz_id)
WHERE deleted = 0 AND biz_type = 'image_generate';
