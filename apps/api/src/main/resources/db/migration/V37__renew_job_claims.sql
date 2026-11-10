-- Platform review M3: a job that ran longer than its hold (salon.jobs.hold, ten minutes) was claimed by another
-- instance and ran twice at once. A running job now renews its claim every third of its hold, through this
-- function, so another instance can claim it only once the holder has stopped renewing for a whole hold.
--
-- Renewing extends the claim only while this holder still has it: once its hold has run out and another instance
-- has claimed the job, the row names that instance and nothing changes. A claim and a renewal of the same job wait
-- for each other on its row, so one of them sees the other's result. Returns whether the holder still holds it.
CREATE FUNCTION job_renew(job_name text, holder text, hold_until timestamptz)
    RETURNS boolean
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    renewed integer;
BEGIN
    UPDATE public.scheduled_job
       SET locked_until = greatest(locked_until, hold_until)
     WHERE name = job_name AND locked_by = holder;
    GET DIAGNOSTICS renewed = ROW_COUNT;
    RETURN renewed = 1;
END
$$;

REVOKE ALL ON FUNCTION job_renew(text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION job_renew(text, text, timestamptz) TO "${application_role}";
