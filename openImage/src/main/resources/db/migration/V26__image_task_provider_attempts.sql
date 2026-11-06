ALTER TABLE image_tasks
ADD COLUMN IF NOT EXISTS provider_attempts JSONB NOT NULL DEFAULT '[]'::jsonb;
