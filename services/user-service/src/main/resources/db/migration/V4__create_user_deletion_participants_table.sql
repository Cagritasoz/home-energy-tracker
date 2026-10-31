-- user_deletion_participants: which services must confirm that they removed a user's data before the deletion
-- can finish.
--
-- The promoter writes one row per required participant in the same transaction that sets DELETING, so the set is
-- fixed when the cleanup starts: a later configuration change never alters a deletion already running. The
-- finalizer waits while a user still has a row with neither acked_at nor waived_at.

CREATE TABLE user_deletion_participants (
    user_id     uuid        NOT NULL,
    participant text        NOT NULL,
    acked_at    timestamptz,    -- the participant confirmed (set by the consumer of its "deleted" event)
    waived_at   timestamptz,    -- a later migration released a participant retired for good; separate from acked_at so an audit can tell them apart

    CONSTRAINT pk_user_deletion_participants PRIMARY KEY (user_id, participant),

    -- User rows are soft deleted and never removed, so there is no ON DELETE rule.
    CONSTRAINT fk_user_deletion_participants_user FOREIGN KEY (user_id) REFERENCES users (id),

    -- A typo such as "Devices " must not become a participant nobody will ever confirm.
    CONSTRAINT chk_user_deletion_participants_name CHECK (participant ~ '^[a-z][a-z0-9_-]{0,62}$'),

    CONSTRAINT chk_user_deletion_participants_acked_or_waived CHECK (acked_at IS NULL OR waived_at IS NULL)
);

-- The primary key serves "pending rows of one user" (the finalizer). This serves "who is still missing" for the
-- watchdog metric, and stays small because rows leave it as soon as they are acked or waived.
CREATE INDEX idx_user_deletion_participants_pending
    ON user_deletion_participants (participant) WHERE acked_at IS NULL AND waived_at IS NULL;
