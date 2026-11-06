ALTER TABLE image_type_configs
  ADD COLUMN IF NOT EXISTS default_size VARCHAR(32) NOT NULL DEFAULT '1024x1024';

UPDATE image_type_configs
SET default_model = 'gpt-image-2',
    fallback_model = 'java2d-placeholder-v1',
    default_size = '1024x1024',
    updated_at = now()
WHERE deleted = 0
  AND image_type IN ('main','scenario','features','dimensions','steps','comparison','package');
