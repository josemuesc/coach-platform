-- Lote D: password recovery without e-mail, session invalidation on password change, account audit.
--  * app_user.password_changed_at / password_change_method: a JWT carries the epoch (millis) of the password it was issued under;
--    changing or resetting the password moves it, so every earlier token stops working. NULL = never changed. /me exposes both so
--    the frontend can warn a student whose password was last set through a coach's reset link.
--  * password_reset: the one-time link the coach hands over. Only the SHA-256 of the token is stored. At most ONE open link per login.
--    Rows are append-only apart from the single NULL -> value transition of used_at / revoked_at; they are never deleted.
--  * account_audit: who touched whose login and when. Immutable. Holds no token, no password, no IP.
--  * Tenant isolation in the schema: every account referenced by these tables must belong to the SAME coach as the row
--    (composite foreign keys on (user, coach_id)), so a link or an audit line can never point at another coach's account.

ALTER TABLE app_user ADD COLUMN password_changed_at     TIMESTAMPTZ NULL;
ALTER TABLE app_user ADD COLUMN password_change_method VARCHAR(12) NULL;

-- COALESCE: a NULL method makes `IN (...)` NULL, and a CHECK passes on NULL, so it must count as false.
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_password_change CHECK (COALESCE(
    (password_changed_at IS NULL AND password_change_method IS NULL)
    OR (password_changed_at IS NOT NULL AND password_change_method IN ('SELF', 'COACH_LINK')), FALSE));

-- app_user.coach_id is NOT NULL (V1), so (id, coach_id) can be the target of composite foreign keys.
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_id_coach UNIQUE (id, coach_id);

CREATE TABLE password_reset (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id           UUID        NOT NULL REFERENCES coach (id),
    student_id         UUID        NOT NULL,
    user_id            UUID        NOT NULL,
    token_hash         VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_by_user_id UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ NULL,
    revoked_at         TIMESTAMPTZ NULL,
    CONSTRAINT ck_password_reset_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_password_reset_one_end CHECK (used_at IS NULL OR revoked_at IS NULL),
    UNIQUE (id, coach_id),
    FOREIGN KEY (student_id, coach_id)         REFERENCES student (id, coach_id),
    FOREIGN KEY (user_id, coach_id)            REFERENCES app_user (id, coach_id),
    FOREIGN KEY (created_by_user_id, coach_id) REFERENCES app_user (id, coach_id)
);
-- one open (not used, not revoked) link per login; an expired-but-unrevoked one still occupies it until replaced
CREATE UNIQUE INDEX uq_password_reset_open ON password_reset (user_id) WHERE used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX idx_password_reset_student ON password_reset (student_id, created_at DESC);

-- The only change a reset link may ever undergo: used_at or revoked_at going from NULL to a value. Nothing else is writable, a
-- used / revoked link can never be revived or have its end moved, and rows are never deleted.
CREATE FUNCTION password_reset_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.coach_id IS DISTINCT FROM OLD.coach_id
        OR NEW.student_id IS DISTINCT FROM OLD.student_id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
        OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
        OR (OLD.used_at IS NOT NULL AND NEW.used_at IS DISTINCT FROM OLD.used_at)
        OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
        RAISE EXCEPTION 'password_reset rows only allow used_at or revoked_at to go from NULL to a value'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_password_reset_guard
    BEFORE UPDATE ON password_reset
    FOR EACH ROW EXECUTE FUNCTION password_reset_guard();
CREATE TRIGGER trg_password_reset_no_delete
    BEFORE DELETE ON password_reset
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();
CREATE TRIGGER trg_password_reset_no_truncate
    BEFORE TRUNCATE ON password_reset
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_row_change();

CREATE TABLE account_audit (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id       UUID        NOT NULL REFERENCES coach (id),
    event          VARCHAR(20) NOT NULL CHECK (event IN
        ('RESET_LINK_CREATED', 'RESET_LINK_USED', 'RESET_LINK_REVOKED', 'PASSWORD_CHANGED')),
    target_user_id UUID        NOT NULL,
    actor_user_id  UUID        NOT NULL,
    student_id     UUID        NULL,
    reset_id       UUID        NULL,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- the coach creates / revokes a link FOR somebody else's login; the account itself uses a link or changes its own password
    CONSTRAINT ck_account_audit_shape CHECK (COALESCE(
        (event = 'PASSWORD_CHANGED' AND reset_id IS NULL AND student_id IS NULL AND actor_user_id = target_user_id)
        OR (event IN ('RESET_LINK_CREATED', 'RESET_LINK_REVOKED') AND reset_id IS NOT NULL AND student_id IS NOT NULL
            AND actor_user_id <> target_user_id)
        OR (event = 'RESET_LINK_USED' AND reset_id IS NOT NULL AND student_id IS NOT NULL AND actor_user_id = target_user_id),
        FALSE)),
    FOREIGN KEY (target_user_id, coach_id) REFERENCES app_user (id, coach_id),
    FOREIGN KEY (actor_user_id, coach_id)  REFERENCES app_user (id, coach_id),
    FOREIGN KEY (reset_id, coach_id)       REFERENCES password_reset (id, coach_id),
    FOREIGN KEY (student_id, coach_id)     REFERENCES student (id, coach_id)
);
CREATE INDEX idx_account_audit_target ON account_audit (target_user_id, occurred_at DESC);
CREATE INDEX idx_account_audit_coach ON account_audit (coach_id, occurred_at DESC);

CREATE TRIGGER trg_account_audit_immutable
    BEFORE UPDATE OR DELETE ON account_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();
CREATE TRIGGER trg_account_audit_no_truncate
    BEFORE TRUNCATE ON account_audit
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_row_change();

ALTER TABLE password_reset ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_audit  ENABLE ROW LEVEL SECURITY;
