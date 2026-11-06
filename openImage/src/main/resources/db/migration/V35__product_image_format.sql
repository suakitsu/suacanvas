ALTER TABLE product_images
  ADD COLUMN IF NOT EXISTS image_format VARCHAR(16);

UPDATE product_images
SET image_format = CASE
  WHEN lower(content_type) = 'image/jpeg' THEN 'jpeg'
  WHEN lower(content_type) = 'image/png' THEN 'png'
  WHEN lower(content_type) = 'image/webp' THEN 'webp'
  ELSE image_format
END
WHERE image_format IS NULL;
