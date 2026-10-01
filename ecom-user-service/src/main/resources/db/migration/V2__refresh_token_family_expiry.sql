-- Absolute session lifetime: fixed at login, carried unchanged into every rotated token, and caps
-- each token's expires_at, so a family that keeps refreshing still ends.
-- Expand step: nullable so instances still on the previous version can keep inserting during a
-- rolling deploy (the application treats NULL as "start the cap now"). A later migration sets NOT NULL.
ALTER TABLE refresh_tokens ADD COLUMN family_expires_at TIMESTAMPTZ;

-- Existing families: login time (oldest surviving row; cleanup only ever deletes whole families)
-- + 30 days, the default ecom.auth.session-max-lifetime. Never earlier than the family's current
-- expiry, so no live session is cut short by the deploy itself.
UPDATE refresh_tokens t
SET family_expires_at = f.family_expires_at
FROM (
    SELECT family_id, GREATEST(MIN(created_at) + INTERVAL '30 days', MAX(expires_at)) AS family_expires_at
    FROM refresh_tokens
    GROUP BY family_id
) f
WHERE t.family_id = f.family_id;
