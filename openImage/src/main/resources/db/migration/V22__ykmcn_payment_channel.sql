INSERT INTO payment_channels (id, name, code, visible, enabled, sort_order, config)
VALUES (
  'channel_ykmcn',
  '易支付',
  'ykmcn',
  false,
  false,
  6,
  '{
    "mode": "ykmcn_legacy",
    "base_url": "https://pay.ykmcn.com",
    "pid": "",
    "key": "",
    "type": "alipay",
    "label": "立即支付",
    "notify_url": "http://127.0.0.1:18080/api/billing/ykmcn/notify",
    "return_url_template": "http://127.0.0.1:13000/credits?order_id={order_id}",
    "name_template": "PicPilot {credits}积分",
    "param_template": "{order_id}",
    "payment_window_minutes": 30,
    "timeout_seconds": 15,
    "instructions": "订单已创建，点击立即支付。支付完成后系统会自动到账，也可以返回本页同步支付状态。"
  }'::jsonb
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name,
  sort_order = EXCLUDED.sort_order,
  config = payment_channels.config || jsonb_build_object(
    'mode', 'ykmcn_legacy',
    'base_url', COALESCE(payment_channels.config->>'base_url', 'https://pay.ykmcn.com'),
    'type', COALESCE(payment_channels.config->>'type', 'alipay'),
    'label', COALESCE(payment_channels.config->>'label', '立即支付'),
    'notify_url', COALESCE(payment_channels.config->>'notify_url', 'http://127.0.0.1:18080/api/billing/ykmcn/notify'),
    'return_url_template', COALESCE(payment_channels.config->>'return_url_template', 'http://127.0.0.1:13000/credits?order_id={order_id}'),
    'name_template', COALESCE(payment_channels.config->>'name_template', 'PicPilot {credits}积分'),
    'param_template', COALESCE(payment_channels.config->>'param_template', '{order_id}'),
    'payment_window_minutes', COALESCE(payment_channels.config->>'payment_window_minutes', '30'),
    'timeout_seconds', COALESCE(payment_channels.config->>'timeout_seconds', '15'),
    'instructions', COALESCE(payment_channels.config->>'instructions', '订单已创建，点击立即支付。支付完成后系统会自动到账，也可以返回本页同步支付状态。')
  ),
  updated_at = now(),
  deleted = 0;
