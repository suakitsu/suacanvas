INSERT INTO payment_channels (id, name, code, visible, enabled, sort_order, config)
VALUES (
  'channel_afdian',
  '爱发电',
  'afdian',
  true,
  true,
  5,
  '{
    "mode": "manual_link",
    "payment_url": "https://www.ifdian.net/p/9c65d9cc617011ed81c352540025c377",
    "links": [
      {
        "label": "爱发电入口 1",
        "url": "https://www.ifdian.net/p/9c65d9cc617011ed81c352540025c377"
      },
      {
        "label": "爱发电入口 2",
        "url": "https://www.ifdian.net/p/010ff078177211eca44f52540025c377"
      }
    ],
    "instructions": "请在爱发电付款备注完整填写 OpenImage 订单号：{order_id}。支付成功后系统会自动同步到账；如果未填写订单号，需要管理员人工确认。"
  }'::jsonb
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name,
  visible = true,
  enabled = true,
  sort_order = EXCLUDED.sort_order,
  config = EXCLUDED.config,
  updated_at = now(),
  deleted = 0;
