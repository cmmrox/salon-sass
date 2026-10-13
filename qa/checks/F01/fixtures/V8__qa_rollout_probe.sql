-- QA-only additive rollout fixture. This is never an application migration or published image.
-- It proves that rollback of an older image leaves the newer database state in place.
CREATE TABLE qa_rollout_probe (marker text PRIMARY KEY);
INSERT INTO qa_rollout_probe VALUES ('newer-schema-retained');
