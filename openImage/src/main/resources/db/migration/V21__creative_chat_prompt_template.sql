INSERT INTO prompt_templates (id,name,scene,version,content,enabled)
VALUES
('prompt_creative_chat','对话生图 Prompt 整理策略','prompt/creative-chat',1,'Convert recent user chat context and the latest revision request into one final ecommerce image prompt. Preserve reference images as the subject authority, resolve conflicts in favor of the latest request, keep composition/lighting/style concrete, leave overlay-safe space, and do not invent unconfirmed product facts or direct-render long text.',true)
ON CONFLICT (id) DO NOTHING;
