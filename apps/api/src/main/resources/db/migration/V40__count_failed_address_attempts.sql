-- Decision 120, the lead's ruling on V38: the limit per address across salons counts failed sign-ins, not every
-- sign-in. Many salons' staff reach us through one carrier-grade NAT address and sign in successfully, so they do
-- not use it up; password spraying (a guess or two at each of many accounts, which never trips one account's limit)
-- fails and does. An attempt is still counted when it starts, before its password is checked, so that it counts
-- even if what follows fails; a sign-in whose password proves correct gives its count back in the transaction that
-- records its outcome. A count can therefore return to zero.

ALTER TABLE shared_address_attempt DROP CONSTRAINT shared_address_attempt_counted;
ALTER TABLE shared_address_attempt ADD CONSTRAINT shared_address_attempt_counted CHECK (attempts >= 0);

-- Gives back one attempt that address counted at counted_at, for that purpose: one atomic statement on that
-- address's row only, never below zero, and only while the window it was counted in is still the row's (an attempt
-- counted in a window that has since ended is not taken from the next one). Joins the caller's transaction. Returns
-- the attempts left in the window.
CREATE FUNCTION shared_return_address_attempt(attempt_purpose text, address_hash text, counted_at timestamptz)
    RETURNS integer
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    UPDATE public.shared_address_attempt a
       SET attempts = greatest(a.attempts - 1, 0), updated_at = greatest(a.updated_at, counted_at)
     WHERE a.purpose = attempt_purpose AND a.key_hash = address_hash
       AND a.window_start <= counted_at AND counted_at < a.window_ends_at
    RETURNING a.attempts
$$;

REVOKE ALL ON FUNCTION shared_return_address_attempt(text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shared_return_address_attempt(text, text, timestamptz) TO "${application_role}";
