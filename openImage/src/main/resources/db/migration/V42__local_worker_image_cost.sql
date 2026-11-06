UPDATE image_tasks
SET actual_cost = 0.001000,
    provider_attempts = CASE
      WHEN provider_attempts IS NULL OR jsonb_typeof(provider_attempts) <> 'array' THEN provider_attempts
      ELSE (
        SELECT COALESCE(jsonb_agg(
          CASE
            WHEN item ->> 'provider' = 'local-cliproxy-image'
              AND (item ->> 'success' = 'true' OR item ->> 'status' = 'success')
            THEN jsonb_set(
              jsonb_set(
                jsonb_set(
                  jsonb_set(item, '{actual_cost_cny}', '0.001000'::jsonb, true),
                  '{upstream_cost}', '0.001000'::jsonb, true
                ),
                '{upstream_cost_unit}', '"CNY"'::jsonb, true
              ),
              '{cost_source}', '"local_worker_fixed"'::jsonb, true
            )
            ELSE item
          END
          ORDER BY ordinal
        ), '[]'::jsonb)
        FROM jsonb_array_elements(provider_attempts) WITH ORDINALITY AS attempts(item, ordinal)
      )
    END
WHERE provider = 'local-cliproxy-image'
  AND status = 'success'
  AND COALESCE(actual_cost, 0) = 0;
