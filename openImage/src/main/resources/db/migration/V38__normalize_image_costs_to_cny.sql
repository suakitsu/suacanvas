-- Image task actual_cost is stored in CNY. Normalize historical estimates to
-- the current provider price policy used by the administrator reports.
UPDATE image_tasks
SET actual_cost = 0.020000
WHERE status = 'success'
  AND lower(coalesce(provider, '')) LIKE '%rua%'
  AND created_at >= TIMESTAMPTZ '2026-07-27 00:00:00+08';

UPDATE image_tasks
SET actual_cost = CASE
    WHEN actual_cost BETWEEN 0.125999 AND 0.126001 THEN 0.142613
    WHEN actual_cost BETWEEN 0.083999 AND 0.084001 THEN 0.095075
    WHEN actual_cost BETWEEN 0.041999 AND 0.042001 THEN 0.057724
    ELSE actual_cost
END
WHERE status = 'success'
  AND (
    lower(coalesce(provider, '')) LIKE '%aishuch%'
    OR lower(coalesce(provider, '')) LIKE '%apimart%'
  );

-- APIMart video task responses use cost=USD and credits_cost=APIMart Credits.
-- Preserve the original upstream amount in administrator-only metadata and
-- normalize the stored actual_cost to CNY for reporting.
UPDATE video_tasks
SET metadata = coalesce(metadata, '{}'::jsonb)
    || CASE
        WHEN coalesce(metadata->>'upstream_credits_cost', '') ~ '^[0-9]+([.][0-9]+)?$'
          THEN jsonb_build_object(
            'upstream_cost', (metadata->>'upstream_credits_cost')::numeric,
            'upstream_cost_unit', 'APIMART_CREDIT',
            'cost_conversion_rate', 0.679110,
            'cost_source', 'upstream_credits_cost'
          )
        ELSE jsonb_build_object(
            'upstream_cost', actual_cost,
            'upstream_cost_unit', 'USD',
            'cost_conversion_rate', 6.791100,
            'cost_source', 'upstream_cost'
          )
      END
    || jsonb_build_object('cost_normalized_to_cny', true),
    actual_cost = round(
      CASE
        WHEN coalesce(metadata->>'upstream_credits_cost', '') ~ '^[0-9]+([.][0-9]+)?$'
          THEN (metadata->>'upstream_credits_cost')::numeric * 0.679110
        ELSE actual_cost * 6.791100
      END,
      6
    )
WHERE status = 'completed'
  AND lower(coalesce(provider, '')) IN ('gemini', 'seedance')
  AND actual_cost IS NOT NULL
  AND actual_cost > 0
  AND NOT (coalesce(metadata, '{}'::jsonb) ? 'cost_normalized_to_cny');
