CREATE TABLE IF NOT EXISTS recharge_plans (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  credits INTEGER NOT NULL,
  price_cents INTEGER NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
  enabled BOOLEAN NOT NULL DEFAULT true,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS payment_channels (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  code VARCHAR(64) NOT NULL UNIQUE,
  visible BOOLEAN NOT NULL DEFAULT true,
  enabled BOOLEAN NOT NULL DEFAULT true,
  sort_order INTEGER NOT NULL DEFAULT 0,
  config JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS recharge_orders (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL REFERENCES users(id),
  plan_id VARCHAR(64) REFERENCES recharge_plans(id),
  channel_code VARCHAR(64) NOT NULL,
  amount_cents INTEGER NOT NULL,
  credits INTEGER NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  remark VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS image_type_configs (
  id VARCHAR(64) PRIMARY KEY,
  image_type VARCHAR(64) NOT NULL UNIQUE,
  image_name VARCHAR(120) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT true,
  credits_cost INTEGER NOT NULL DEFAULT 0,
  default_model VARCHAR(128) NOT NULL DEFAULT '',
  fallback_model VARCHAR(128) NOT NULL DEFAULT '',
  required_fields VARCHAR(255) NOT NULL DEFAULT '',
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0
);

INSERT INTO recharge_plans (id,name,credits,price_cents,currency,enabled,sort_order) VALUES
('plan_trial','体验包',50,1900,'CNY',true,1),
('plan_basic','基础包',300,9900,'CNY',true,2),
('plan_standard','标准包',1000,29900,'CNY',true,3)
ON CONFLICT (id) DO NOTHING;

INSERT INTO payment_channels (id,name,code,visible,enabled,sort_order,config) VALUES
('channel_manual','人工充值','manual',true,true,1,'{"description":"提交订单后由管理员确认到账"}'::jsonb),
('channel_paypal','PayPal','paypal',true,false,2,'{"description":"暂未启用"}'::jsonb),
('channel_antom','Antom','antom',false,false,3,'{}'::jsonb),
('channel_stripe','Stripe','stripe',false,false,4,'{}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO image_type_configs (id,image_type,image_name,enabled,credits_cost,default_model,fallback_model,required_fields,sort_order) VALUES
('imgcfg_main','main','白底主图',true,3,'bg-cleaner','image-v1','主商品图',1),
('imgcfg_scenario','scenario','场景图',true,5,'flux-pro','dalle','使用场景',2),
('imgcfg_features','features','卖点图',true,4,'flux-pro','sdxl','核心卖点',3),
('imgcfg_dimensions','dimensions','尺寸图',true,4,'base-image','none','产品尺寸',4),
('imgcfg_steps','steps','步骤图',true,4,'base-image','none','步骤/包装',5),
('imgcfg_comparison','comparison','对比图',true,5,'base-image','none','痛点/卖点',6),
('imgcfg_package','package','包装/变体图',true,3,'flux-pro','sdxl','颜色/规格',7)
ON CONFLICT (image_type) DO NOTHING;

INSERT INTO prompt_templates (id,name,scene,version,content,enabled) VALUES
('prompt_title','Listing 标题生成','copy/title',3,'基于已确认商品事实生成平台合规标题。',true),
('prompt_bullets','五点卖点生成','copy/bullets',5,'基于商品事实生成五点卖点，禁止夸大功效。',true),
('prompt_image_brief','七图 Brief 生成','image/brief',2,'为每个图类型生成视觉 Brief，保留合规约束。',true)
ON CONFLICT (id) DO NOTHING;
