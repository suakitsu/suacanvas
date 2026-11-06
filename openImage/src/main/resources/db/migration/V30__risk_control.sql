CREATE TABLE IF NOT EXISTS risk_events (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES users(id),
    project_id VARCHAR(64),
    task_id VARCHAR(64),
    feature VARCHAR(80) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    keyword VARCHAR(120) NOT NULL,
    matched_text TEXT,
    source VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_risk_events_user_created ON risk_events(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_risk_events_level_created ON risk_events(risk_level, created_at DESC);

INSERT INTO system_settings (id, setting_key, setting_value, description)
VALUES (
    'risk-control',
    'risk_control',
    '{
      "enabled": true,
      "ban_threshold": 5,
      "block_levels": ["high", "medium"],
      "warn_levels": ["low"],
      "keywords": {
        "high": ["色情", "成人内容", "裸露", "全裸", "裸体", "露点", "性爱", "性交", "性行为", "援交", "卖淫", "未成年裸露", "儿童色情", "幼女裸", "萝莉裸", "血腥", "肢解", "尸体", "自残", "自杀", "虐待", "毒品", "吸毒", "贩毒", "大麻", "冰毒", "海洛因", "可卡因", "枪支", "手枪", "步枪", "炸弹", "爆炸物", "制爆", "杀人", "身份证", "护照", "银行卡", "信用卡", "验证码", "密码", "私钥", "纳粹", "恐怖主义", "极端组织"],
        "medium": ["仿牌", "高仿", "复刻", "原单", "尾单", "莆田", "山寨", "假冒", "侵权", "logo复刻", "商标复刻", "大牌同款", "明星真人", "名人脸", "网红真人", "换脸", "AI换脸", "deepfake", "迪士尼", "漫威", "宝可梦", "皮卡丘", "Hello Kitty", "奥特曼", "Nike", "Adidas", "Gucci", "LV", "Chanel", "Dior", "Prada", "Hermes", "政治敏感", "领导人", "宗教攻击", "医疗功效", "治愈", "根治", "减肥神药", "壮阳"],
        "low": ["性感", "泳装", "内衣", "比基尼", "儿童", "小孩", "宝宝", "刀", "军事", "战争", "品牌同款", "大牌风", "奢侈品风"]
      }
    }'::jsonb,
    '风控关键词与封禁阈值'
) ON CONFLICT (setting_key) DO NOTHING;
