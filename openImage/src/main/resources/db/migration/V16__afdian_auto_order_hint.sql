UPDATE payment_channels
SET
  config = jsonb_set(
    jsonb_set(
      jsonb_set(
        jsonb_set(
          COALESCE(config, '{}'::jsonb),
          '{remark_template}',
          '"{order_id}"'::jsonb,
          true
        ),
        '{custom_order_id_template}',
        '"{order_id}"'::jsonb,
        true
      ),
      '{return_url_template}',
      '"http://127.0.0.1:13000/credits?payment_return=1&order_id={order_id}"'::jsonb,
      true
    ),
    '{instructions}',
    '"系统已自动把 OpenImage 订单号带入爱发电订单：{order_id}。请确认 9.90 元套餐和金额后付款，支付成功后可点击同步支付状态。"'::jsonb,
    true
  ),
  updated_at = now()
WHERE code = 'afdian';
