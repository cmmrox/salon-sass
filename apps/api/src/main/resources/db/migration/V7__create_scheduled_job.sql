-- The scheduler's bookkeeping: one row per recurring job, saying when it next runs and which
-- instance of the API holds it while it runs. A platform table (no tenant_id), which the
-- application's role cannot read; it reaches it only through the two functions below, as it
-- reaches the tenant registry through tenant_by_slug(). Every time is the application's clock,
-- passed in.

CREATE TABLE scheduled_job (
    name             text        NOT NULL,
    -- The occurrence the job is waiting for.
    next_run_at      timestamptz NOT NULL,
    -- While it runs: the instance that claimed it, and until when the claim holds if that instance
    -- stops without saying it has finished.
    locked_by        text,
    locked_until     timestamptz,
    last_started_at  timestamptz,
    last_finished_at timestamptz,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT scheduled_job_pkey PRIMARY KEY (name),
    CONSTRAINT scheduled_job_name_format CHECK (name ~ '^[a-z][a-z0-9-]{1,62}$'),
    CONSTRAINT scheduled_job_lock_complete CHECK ((locked_by IS NULL) = (locked_until IS NULL))
);

REVOKE ALL ON TABLE scheduled_job FROM PUBLIC;

-- Claims the job's occurrence that is due, if any, for one instance. Exactly one caller can claim
-- an occurrence: the row is locked and moved on to the first occurrence after at_time in one
-- statement, so a job runs at most once per occurrence across every instance, and occurrences
-- missed while nothing was running are run once, not once each. A job that is still held by an
-- instance whose claim has not run out is not claimed. Returns the occurrence claimed (so the
-- caller can tell how late it started), or null.
CREATE FUNCTION job_claim(job_name text, at_time timestamptz, every interval, holder text, hold_until timestamptz)
    RETURNS timestamptz
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    due timestamptz;
BEGIN
    IF every <= interval '0' THEN
        RAISE EXCEPTION 'a job must run at a positive interval';
    END IF;
    INSERT INTO public.scheduled_job (name, next_run_at, created_at, updated_at)
        VALUES (job_name, at_time, at_time, at_time)
        ON CONFLICT (name) DO NOTHING;
    SELECT j.next_run_at INTO due FROM public.scheduled_job j
     WHERE j.name = job_name AND j.next_run_at <= at_time
       AND (j.locked_until IS NULL OR j.locked_until <= at_time)
       FOR UPDATE SKIP LOCKED;
    IF due IS NULL THEN
        RETURN NULL;
    END IF;
    UPDATE public.scheduled_job j
       SET next_run_at = due + every * (floor(extract(epoch FROM at_time - due) / extract(epoch FROM every)) + 1),
           locked_by = holder, locked_until = hold_until, last_started_at = at_time, updated_at = at_time
     WHERE j.name = job_name;
    RETURN due;
END
$$;

REVOKE ALL ON FUNCTION job_claim(text, timestamptz, interval, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION job_claim(text, timestamptz, interval, text, timestamptz) TO "${application_role}";

-- The instance that claimed the job says it has finished, and lets go of it.
CREATE FUNCTION job_finish(job_name text, holder text, at_time timestamptz)
    RETURNS void
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    UPDATE public.scheduled_job
       SET locked_by = NULL, locked_until = NULL, last_finished_at = at_time, updated_at = at_time
     WHERE name = job_name AND locked_by = holder
$$;

REVOKE ALL ON FUNCTION job_finish(text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION job_finish(text, text, timestamptz) TO "${application_role}";
