-- Platform review M6: a refresh looked its session up by refresh_hash, and a replay looked its spent credential
-- up by credential_hash, with no index for either, so every refresh scanned all of a salon's sessions (and an
-- unknown credential its whole refresh history too), under the salon's identity lock. Sessions and refresh
-- history were also never removed. These indexes make each lookup one probe; identity-security-cleanup now
-- removes sessions some days after they ended, their refresh history with them (ON DELETE CASCADE, V8).
--
-- Additive, so the previous version keeps running against it. Every index starts with tenant_id.

-- Not unique: a refresh credential is 256 random bits, and the lookup needs only the index; a unique one would also
-- turn the isolation tests' fixed fixture hashes into key violations ahead of the foreign keys they prove.
CREATE INDEX identity_session_refresh ON identity_session (tenant_id, refresh_hash);
CREATE INDEX identity_refresh_credential ON identity_refresh_history (tenant_id, credential_hash);

-- What the cleanup asks: sessions that expired, or were revoked, before a given instant.
CREATE INDEX identity_session_expiry ON identity_session (tenant_id, expires_at);
CREATE INDEX identity_session_revoked ON identity_session (tenant_id, revoked_at) WHERE revoked_at IS NOT NULL;
