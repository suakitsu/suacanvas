UPDATE payment_channels
SET visible = false,
    enabled = false,
    updated_at = now()
WHERE code = 'ykmcn' AND deleted = 0;

INSERT INTO payment_channels (id, name, code, visible, enabled, sort_order, config)
VALUES
(
  'channel_ykmcn_alipay',
  '支付宝支付',
  'ykmcn_alipay',
  false,
  false,
  6,
  '{
    "mode": "ykmcn_legacy",
    "base_url": "https://pay.ykmcn.com",
    "pid": "",
    "key": "",
    "type": "alipay",
    "label": "支付宝支付",
    "notify_url": "http://127.0.0.1:18080/api/billing/ykmcn/notify",
    "return_url_template": "http://127.0.0.1:13000/credits?order_id={order_id}",
    "name_template": "PicPilot {credits}积分",
    "param_template": "{order_id}",
    "payment_window_minutes": 30,
    "timeout_seconds": 15,
    "instructions": "订单已创建，点击支付宝支付。支付完成后系统会自动到账，也可以返回本页同步支付状态。"
  }'::jsonb
),
(
  'channel_ykmcn_wxpay',
  '微信支付',
  'ykmcn_wxpay',
  false,
  false,
  7,
  '{
    "mode": "ykmcn_legacy",
    "base_url": "https://pay.ykmcn.com",
    "pid": "",
    "key": "",
    "type": "wxpay",
    "label": "微信支付",
    "notify_url": "http://127.0.0.1:18080/api/billing/ykmcn/notify",
    "return_url_template": "http://127.0.0.1:13000/credits?order_id={order_id}",
    "name_template": "PicPilot {credits}积分",
    "param_template": "{order_id}",
    "payment_window_minutes": 30,
    "timeout_seconds": 15,
    "instructions": "订单已创建，点击微信支付。支付完成后系统会自动到账，也可以返回本页同步支付状态。"
  }'::jsonb
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name,
  sort_order = EXCLUDED.sort_order,
  config = payment_channels.config || jsonb_build_object(
    'mode', 'ykmcn_legacy',
    'base_url', COALESCE(payment_channels.config->>'base_url', EXCLUDED.config->>'base_url'),
    'type', EXCLUDED.config->>'type',
    'label', EXCLUDED.config->>'label',
    'notify_url', COALESCE(payment_channels.config->>'notify_url', EXCLUDED.config->>'notify_url'),
    'return_url_template', COALESCE(payment_channels.config->>'return_url_template', EXCLUDED.config->>'return_url_template'),
    'name_template', COALESCE(payment_channels.config->>'name_template', EXCLUDED.config->>'name_template'),
    'param_template', COALESCE(payment_channels.config->>'param_template', EXCLUDED.config->>'param_template'),
    'payment_window_minutes', COALESCE(payment_channels.config->>'payment_window_minutes', EXCLUDED.config->>'payment_window_minutes'),
    'timeout_seconds', COALESCE(payment_channels.config->>'timeout_seconds', EXCLUDED.config->>'timeout_seconds'),
    'instructions', EXCLUDED.config->>'instructions'
  ),
  updated_at = now(),
  deleted = 0;
