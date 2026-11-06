INSERT INTO system_settings (id, setting_key, setting_value, description)
VALUES (
  'benefit-onboarding',
  'benefit_onboarding',
  '{
    "enabled": true,
    "title": "新人兑换码积分福利",
    "description": "加入 QQ 群或微信群，联系管理员领取兑换码；到积分页兑换后即可用积分体验生成流程。",
    "qq_group": "619616946",
    "wechat_group": "",
    "qq_qr_url": "",
    "wechat_qr_url": "",
    "redeem_tutorial": "1. 加入社群 2. 联系管理员领取兑换码 3. 到积分充值页兑换积分 4. 回到工作台生成图片",
    "free_highlight_label": "兑换码会直接兑换成积分，生成仍按积分扣费"
  }'::jsonb,
  '新人领取弹窗、社群二维码和兑换码积分福利配置'
)
ON CONFLICT (setting_key) DO NOTHING;
