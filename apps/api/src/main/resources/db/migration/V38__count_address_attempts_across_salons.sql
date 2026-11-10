-- Decision 120 (BUG-F02-016, platform review H1): the staff sign-in's limit per address was a row of a salon's
-- table, so one address had 30 attempts in 15 minutes on each salon, and with many salons nearly no limit at all.
-- This counts an address's attempts across every salon: a platform-level table with no tenant_id, owned by
-- shared, holding a purpose, an opaque hash of the address (the caller's keyed hash; never the address), and a
-- count in a window. Neither runtime role has a grant on it: the application's role counts through the first
-- function below, and the platform role's job removes spent counters through the second. Both pin their search
-- path, cannot be run by everyone, and return a count and nothing of any salon.

CREATE TABLE shared_address_attempt (
    purpose        text        NOT NULL,
    key_hash       text        NOT NULL,
    window_start   timestamptz NOT NULL,
    window_ends_at timestamptz NOT NULL,
    attempts       integer     NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT shared_address_attempt_pkey PRIMARY KEY (purpose, key_hash),
    CONSTRAINT shared_address_attempt_purpose_format CHECK (purpose ~ '^[a-z][a-z0-9-]{1,62}$'),
    CONSTRAINT shared_address_attempt_hash_present CHECK (btrim(key_hash) <> ''),
    CONSTRAINT shared_address_attempt_counted CHECK (attempts >= 1),
    CONSTRAINT shared_address_attempt_window CHECK (window_ends_at > window_start)
);

CREATE INDEX shared_address_attempt_spent_idx ON shared_address_attempt (window_ends_at);

REVOKE ALL ON TABLE shared_address_attempt FROM PUBLIC;

-- Counts one attempt for that purpose and address and returns the attempts in its window, this one included: one
-- atomic statement, so simultaneous attempts are counted exactly as the database orders them, as operator sign-in
-- counts its own (OperatorSecurityStore.countAttempt). A window starts on the first attempt and lasts
-- window_length; the first attempt after it has ended starts the next one. Joins the caller's transaction, so a
-- refused attempt's count commits with its other counters.
CREATE FUNCTION shared_count_address_attempt(attempt_purpose text, address_hash text, at_time timestamptz,
        window_length interval)
    RETURNS integer
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    INSERT INTO public.shared_address_attempt AS a
        (purpose, key_hash, window_start, window_ends_at, attempts, created_at, updated_at)
    VALUES (attempt_purpose, address_hash, at_time, at_time + window_length, 1, at_time, at_time)
    ON CONFLICT (purpose, key_hash) DO UPDATE SET
        window_start   = CASE WHEN a.window_ends_at <= EXCLUDED.window_start
                              THEN EXCLUDED.window_start ELSE a.window_start END,
        window_ends_at = CASE WHEN a.window_ends_at <= EXCLUDED.window_start
                              THEN EXCLUDED.window_ends_at ELSE a.window_ends_at END,
        attempts       = CASE WHEN a.window_ends_at <= EXCLUDED.window_start
                              THEN 1 ELSE a.attempts + 1 END,
        updated_at     = EXCLUDED.updated_at
    RETURNING attempts
$$;

REVOKE ALL ON FUNCTION shared_count_address_attempt(text, text, timestamptz, interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shared_count_address_attempt(text, text, timestamptz, interval) TO "${application_role}";

-- The counter's cleanup, for the platform role's job: removes at most max_rows counters whose window ended before
-- at_time, and returns how many. Called in bounded batches until it returns fewer than max_rows.
CREATE FUNCTION shared_remove_spent_address_attempts(at_time timestamptz, max_rows integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    removed integer;
BEGIN
    DELETE FROM public.shared_address_attempt a
     WHERE (a.purpose, a.key_hash) IN (
        SELECT s.purpose, s.key_hash FROM public.shared_address_attempt s
         WHERE s.window_ends_at < at_time
         ORDER BY s.window_ends_at
         LIMIT greatest(max_rows, 0));
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$$;

REVOKE ALL ON FUNCTION shared_remove_spent_address_attempts(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shared_remove_spent_address_attempts(timestamptz, integer) TO "${platform_role}";
