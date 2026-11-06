UPDATE image_type_configs
SET credits_cost = 6,
    updated_at = now()
WHERE deleted = 0
  AND image_type IN ('main', 'scenario', 'features', 'dimensions', 'steps', 'comparison', 'package');
