CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_ledger_video_refund_active
ON credit_ledger(user_id, biz_type, biz_id)
WHERE deleted = 0
  AND biz_id IS NOT NULL
  AND biz_type = 'video_refund';

CREATE OR REPLACE FUNCTION enforce_video_task_fixed_route()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF COALESCE(OLD.provider, '') <> ''
     AND (
       NEW.provider IS DISTINCT FROM OLD.provider
       OR NEW.model IS DISTINCT FROM OLD.model
     ) THEN
    RAISE EXCEPTION 'video task provider and model are immutable after assignment';
  END IF;

  IF COALESCE(OLD.upstream_task_id, '') <> ''
     AND NEW.upstream_task_id IS DISTINCT FROM OLD.upstream_task_id THEN
    RAISE EXCEPTION 'video task upstream id is immutable after submission';
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_video_task_fixed_route ON video_tasks;

CREATE TRIGGER trg_video_task_fixed_route
BEFORE UPDATE OF provider, model, upstream_task_id
ON video_tasks
FOR EACH ROW
EXECUTE FUNCTION enforce_video_task_fixed_route();
