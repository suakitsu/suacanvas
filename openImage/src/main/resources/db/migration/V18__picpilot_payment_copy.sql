UPDATE payment_channels
SET config = jsonb_set(
    config,
    '{instructions}',
    to_jsonb(replace(config->>'instructions', 'OpenImage', 'PicPilot')),
    true
  ),
  updated_at = now()
WHERE code = 'afdian'
  AND deleted = 0
  AND config ? 'instructions'
  AND config->>'instructions' LIKE '%OpenImage%';
