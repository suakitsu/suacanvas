ALTER TABLE recharge_orders
ADD COLUMN IF NOT EXISTS external_trade_no VARCHAR(128),
ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uk_recharge_orders_external_trade_active
ON recharge_orders(channel_code, external_trade_no)
WHERE deleted = 0
  AND external_trade_no IS NOT NULL
  AND external_trade_no <> '';
