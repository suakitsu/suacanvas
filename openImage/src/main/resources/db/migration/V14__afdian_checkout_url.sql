UPDATE payment_channels
SET
  config = jsonb_build_object(
    'mode', 'checkout_link',
    'checkout_url', 'https://ifdian.net/order/create',
    'plan_id', '083db68267ae11f1945352540025c377',
    'product_type', 0,
    'remark_template', '{order_id}',
    'custom_order_id_template', '{order_id}',
    'allowed_plan_ids', jsonb_build_array('plan_afdian_100'),
    'instructions', '请在爱发电付款页确认 9.90 元套餐；系统已自动把 OpenImage 订单号写入付款备注：{order_id}。支付成功后可点击同步支付状态。'
  ),
  updated_at = now()
WHERE code = 'afdian';
