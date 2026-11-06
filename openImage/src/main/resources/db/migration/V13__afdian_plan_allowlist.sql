INSERT INTO recharge_plans (id, name, credits, price_cents, currency, enabled, sort_order)
VALUES ('plan_afdian_100', '爱发电 100 积分包', 100, 990, 'CNY', true, 0)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  credits = EXCLUDED.credits,
  price_cents = EXCLUDED.price_cents,
  currency = EXCLUDED.currency,
  enabled = true,
  sort_order = EXCLUDED.sort_order,
  updated_at = now(),
  deleted = 0;

UPDATE payment_channels
SET
  config = jsonb_set(
    COALESCE(config, '{}'::jsonb),
    '{allowed_plan_ids}',
    '["plan_trial", "plan_basic", "plan_standard"]'::jsonb,
    true
  ),
  updated_at = now()
WHERE code = 'manual';

UPDATE payment_channels
SET
  config = jsonb_set(
    COALESCE(config, '{}'::jsonb),
    '{allowed_plan_ids}',
    '["plan_afdian_100"]'::jsonb,
    true
  ),
  updated_at = now()
WHERE code = 'afdian';
