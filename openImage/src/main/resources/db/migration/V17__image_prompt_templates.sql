INSERT INTO prompt_templates (id,name,scene,version,content,enabled)
VALUES
('prompt_copy_listing','Listing 文案生成总模板','copy/listing',1,'Listing copy must follow Amazon compliance, use only confirmed product facts, preserve the requested output language, and return strict JSON only.',true),
('prompt_image_aplus','A+ 图生图策略','image/aplus',1,'Use an Amazon A+ brand-content layout. Prioritize product identity, clear visual hierarchy, mobile readability, clean negative space for later overlays, realistic lighting, and factual accuracy. Avoid generated text, fake badges, unsupported certifications, competitor logos, and invented parameters.',true),
('prompt_image_creative','自由创作图生图策略','image/creative',1,'Use the user prompt as scene/style direction while preserving reference images as the primary subject when provided. Keep the result commercially usable, realistic, coherent, and free of watermarks, accidental logos, unreadable text, distorted structure, or unrelated objects.',true),
('prompt_image_single','单图通用生图策略','image/single',1,'For single-image generation, preserve product identity from references, keep composition clean, use realistic material and lighting, leave safe margins, and avoid claims or details not confirmed by the user.',true),
('prompt_prompt_optimize','图片 Prompt 优化策略','prompt/optimize',1,'Rewrite user image prompts into concise ecommerce visual briefs: subject, scene, composition, camera, lighting, material cues, background, safe overlay areas, and compliance limits. Do not add unconfirmed product facts.',true)
ON CONFLICT (id) DO NOTHING;

UPDATE prompt_templates
SET content = 'Create detailed Amazon Listing image strategy for each image type. Use confirmed product facts, listing title, selling points, description, keywords, reference images, safe overlay areas, and image-specific composition. Do not render text directly in the image. Do not invent dimensions, materials, certifications, compatibility, package contents, safety claims, medical effects, guarantees, awards, competitor logos, or promotional claims.',
    version = version + 1,
    updated_at = now()
WHERE id = 'prompt_image_brief'
  AND content = '为每个图类型生成视觉 Brief，保留合规约束。';
