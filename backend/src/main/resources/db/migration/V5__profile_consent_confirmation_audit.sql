-- Phase 3.5: student profile and legal guardian; versioned consents that can be revoked (consent_record = acceptances,
-- consent_revocation = revocations; the CURRENT state of a consent is the latest event per student + type); student
-- confirmation of classes (QR / later); an immutable audit of every attendance change; and the coach settings those need.
-- Same conventions as V2/V4: coach_id everywhere, composite FKs (child_id, coach_id), RLS enabled with no policies.
-- V1-V4 are untouched. Timestamps such as accepted_at / revoked_at / occurred_at are passed explicitly by the service from
-- the injected Clock; their DEFAULT now() is only a safety net.

-- ---------------------------------------------------------------------------------------------------------
-- Student: goal, birth date and the legal guardian.
-- birth_date is mandatory for NEW students (enforced by the service: the consent modality depends on it) but stays
-- nullable here so rows created before V5 are valid. "Under 18 => guardian required" depends on today's date, which a
-- CHECK cannot read reliably, so it lives in the service; the database guarantees the four guardian fields come together.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE student
    ADD COLUMN goal                  VARCHAR(500) NULL,
    ADD COLUMN birth_date            DATE         NULL,
    ADD COLUMN guardian_name         VARCHAR(200) NULL,
    ADD COLUMN guardian_relationship VARCHAR(50)  NULL,
    ADD COLUMN guardian_phone        VARCHAR(30)  NULL,
    ADD COLUMN guardian_email        VARCHAR(320) NULL,
    ADD CONSTRAINT ck_student_birth_date CHECK (birth_date IS NULL OR birth_date >= DATE '1900-01-01'),
    ADD CONSTRAINT ck_student_guardian_together CHECK (
        (guardian_name IS NULL) = (guardian_relationship IS NULL)
        AND (guardian_name IS NULL) = (guardian_phone IS NULL)
        AND (guardian_name IS NULL) = (guardian_email IS NULL)),
    ADD CONSTRAINT ck_student_guardian_not_blank CHECK (
        guardian_name IS NULL
        OR (length(btrim(guardian_name)) > 0 AND length(btrim(guardian_relationship)) > 0
            AND length(btrim(guardian_phone)) > 0 AND length(btrim(guardian_email)) > 0)),
    ADD CONSTRAINT ck_student_guardian_email_lower CHECK (guardian_email IS NULL OR guardian_email = lower(guardian_email));

-- ---------------------------------------------------------------------------------------------------------
-- Coach settings: confirmation window, QR access window, and the coach's own record that they hold the gym's
-- informed consent (a flag and a date; the content is never stored).
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE coach_settings
    ADD COLUMN confirmation_window_hours  INT         NOT NULL DEFAULT 72 CHECK (confirmation_window_hours BETWEEN 1 AND 720),
    ADD COLUMN qr_open_minutes_before     INT         NOT NULL DEFAULT 15 CHECK (qr_open_minutes_before BETWEEN 0 AND 120),
    ADD COLUMN qr_close_hours_after_end   INT         NOT NULL DEFAULT 2  CHECK (qr_close_hours_after_end BETWEEN 0 AND 24),
    ADD COLUMN gym_consent_confirmed      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN gym_consent_confirmed_at   TIMESTAMPTZ NULL,
    ADD CONSTRAINT ck_coach_settings_gym_consent CHECK (gym_consent_confirmed = (gym_consent_confirmed_at IS NOT NULL));

-- ---------------------------------------------------------------------------------------------------------
-- Student confirmation of an attendance. Permanent; on its own it never changes the status nor the cycle count.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE session_attendance
    ADD COLUMN student_confirmed_at       TIMESTAMPTZ NULL,
    ADD COLUMN student_confirmation_method VARCHAR(10) NULL CHECK (student_confirmation_method IN ('QR', 'LATER')),
    ADD CONSTRAINT ck_attendance_confirmation CHECK ((student_confirmed_at IS NULL) = (student_confirmation_method IS NULL));

-- ---------------------------------------------------------------------------------------------------------
-- Immutability. search_path is pinned so the functions cannot be hijacked through a schema.
-- forbid_row_change is used both per row (UPDATE / DELETE) and per statement (TRUNCATE).
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION forbid_row_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
AS $$
BEGIN
    RAISE EXCEPTION 'rows of % are immutable: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

-- Protects ONLY the two confirmation columns of session_attendance (not the rest of the row): once a student has
-- confirmed, the date and the method can neither change nor disappear, while status, cycle, cancellation... stay editable.
CREATE FUNCTION forbid_confirmation_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
AS $$
BEGIN
    IF OLD.student_confirmed_at IS NOT NULL
       AND (NEW.student_confirmed_at IS DISTINCT FROM OLD.student_confirmed_at
            OR NEW.student_confirmation_method IS DISTINCT FROM OLD.student_confirmation_method) THEN
        RAISE EXCEPTION 'the student confirmation of an attendance is permanent'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_attendance_confirmation_permanent
    BEFORE UPDATE ON session_attendance
    FOR EACH ROW EXECUTE FUNCTION forbid_confirmation_change();

-- ---------------------------------------------------------------------------------------------------------
-- consent_record: one row per authorization ACCEPTED, by type and version. The hash is of the exact text shown. There is
-- deliberately NO ip column. For DATA_GUARDIAN the signer (name and relationship of the guardian on file at that moment)
-- is stored too; for the other types the signer columns must be empty. Not unique per version: after a revocation the same
-- version can be accepted again. Immutable: nothing is edited, deleted or truncated.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE consent_record (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id            UUID         NOT NULL REFERENCES coach (id),
    student_id          UUID         NOT NULL,
    type                VARCHAR(20)  NOT NULL CHECK (type IN ('DATA_ADULT', 'DATA_GUARDIAN', 'WHATSAPP')),
    version             VARCHAR(30)  NOT NULL CHECK (length(btrim(version)) > 0),
    text_sha256         VARCHAR(64)  NOT NULL CHECK (text_sha256 ~ '^[0-9a-f]{64}$'),
    signer_name         VARCHAR(200) NULL,
    signer_relationship VARCHAR(50)  NULL,
    accepted_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    accepted_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    CONSTRAINT ck_consent_signer CHECK (
        (type = 'DATA_GUARDIAN'
            AND signer_name IS NOT NULL AND length(btrim(signer_name)) > 0
            AND signer_relationship IS NOT NULL AND length(btrim(signer_relationship)) > 0)
        OR (type <> 'DATA_GUARDIAN' AND signer_name IS NULL AND signer_relationship IS NULL)),
    FOREIGN KEY (student_id, coach_id) REFERENCES student (id, coach_id)
);
CREATE INDEX idx_consent_student ON consent_record (student_id, type, accepted_at DESC);
CREATE INDEX idx_consent_student_type_version ON consent_record (student_id, type, version);

CREATE TRIGGER trg_consent_record_immutable
    BEFORE UPDATE OR DELETE ON consent_record
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();
CREATE TRIGGER trg_consent_record_no_truncate
    BEFORE TRUNCATE ON consent_record
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_row_change();

-- ---------------------------------------------------------------------------------------------------------
-- consent_revocation: one row per authorization REVOKED. The current state of a consent (student + type) is whichever
-- event is the latest, acceptance or revocation. The service refuses to accept an already-active consent and to revoke one
-- that is not active, under the student's row lock. Immutable.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE consent_revocation (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id           UUID         NOT NULL REFERENCES coach (id),
    student_id         UUID         NOT NULL,
    type               VARCHAR(20)  NOT NULL CHECK (type IN ('DATA_ADULT', 'DATA_GUARDIAN', 'WHATSAPP')),
    revoked_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    FOREIGN KEY (student_id, coach_id) REFERENCES student (id, coach_id)
);
CREATE INDEX idx_consent_revocation_student ON consent_revocation (student_id, type, revoked_at DESC);

CREATE TRIGGER trg_consent_revocation_immutable
    BEFORE UPDATE OR DELETE ON consent_revocation
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();
CREATE TRIGGER trg_consent_revocation_no_truncate
    BEFORE TRUNCATE ON consent_revocation
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_row_change();

-- ---------------------------------------------------------------------------------------------------------
-- attendance_audit: who did what to an attendance, how, and when. One row per BOOK / RESCHEDULE / MARK / CANCEL /
-- CONFIRM / TRANSFER, written in the same transaction as the change. ck_audit_shape repeats AttendanceAuditRules: a
-- combination no business flow produces cannot be stored. TRANSFER = the coach who records a renewal payment moves a
-- still-scheduled attendance to the new cycle (status unchanged, reason required). Immutable. No IP is kept.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE attendance_audit (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id       UUID         NOT NULL REFERENCES coach (id),
    attendance_id  UUID         NOT NULL,
    action         VARCHAR(12)  NOT NULL CHECK (action IN ('BOOK', 'RESCHEDULE', 'MARK', 'CANCEL', 'CONFIRM', 'TRANSFER')),
    previous_status VARCHAR(30) NULL CHECK (previous_status IN
        ('SCHEDULED', 'ATTENDED', 'CANCELLED_ON_TIME', 'RESCHEDULED', 'NO_SHOW', 'CANCELLED_BY_COACH')),
    new_status     VARCHAR(30)  NOT NULL CHECK (new_status IN
        ('SCHEDULED', 'ATTENDED', 'CANCELLED_ON_TIME', 'RESCHEDULED', 'NO_SHOW', 'CANCELLED_BY_COACH')),
    method         VARCHAR(10)  NOT NULL CHECK (method IN ('COACH', 'STUDENT', 'QR', 'LATER')),
    actor_user_id  UUID         NOT NULL REFERENCES app_user (id),
    actor_role     VARCHAR(10)  NOT NULL CHECK (actor_role IN ('COACH', 'STUDENT')),
    reason         VARCHAR(500) NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- COALESCE: a NULL previous_status makes some branches NULL, and a CHECK passes on NULL, so it must count as false.
    CONSTRAINT ck_audit_shape CHECK (COALESCE(
        (action = 'BOOK' AND previous_status IS NULL AND new_status = 'SCHEDULED' AND method = actor_role)
        OR (action = 'MARK'
            AND previous_status IN ('SCHEDULED', 'ATTENDED', 'NO_SHOW')
            AND new_status IN ('ATTENDED', 'NO_SHOW')
            AND previous_status <> new_status
            AND ((actor_role = 'COACH' AND method = 'COACH')
                 OR (actor_role = 'STUDENT' AND method = 'QR' AND previous_status = 'SCHEDULED' AND new_status = 'ATTENDED')))
        OR (action = 'CANCEL' AND previous_status = 'SCHEDULED'
            AND ((new_status = 'CANCELLED_ON_TIME' AND actor_role = 'STUDENT' AND method = 'STUDENT')
                 OR (new_status = 'CANCELLED_BY_COACH' AND actor_role = 'COACH' AND method = 'COACH')))
        OR (action = 'RESCHEDULE' AND previous_status = 'SCHEDULED' AND new_status = 'RESCHEDULED'
            AND actor_role = 'STUDENT' AND method = 'STUDENT')
        OR (action = 'CONFIRM' AND previous_status IN ('SCHEDULED', 'ATTENDED', 'NO_SHOW') AND previous_status = new_status
            AND actor_role = 'STUDENT' AND method IN ('QR', 'LATER'))
        OR (action = 'TRANSFER' AND previous_status = 'SCHEDULED' AND new_status = 'SCHEDULED'
            AND actor_role = 'COACH' AND method = 'COACH'), FALSE)),
    -- a reason is required when the coach cancels and for every TRANSFER
    CONSTRAINT ck_audit_reason_required CHECK
        ((action <> 'TRANSFER' AND new_status <> 'CANCELLED_BY_COACH')
         OR (reason IS NOT NULL AND length(btrim(reason)) > 0)),
    FOREIGN KEY (attendance_id, coach_id) REFERENCES session_attendance (id, coach_id)
);
CREATE INDEX idx_attendance_audit_attendance ON attendance_audit (attendance_id, occurred_at);
CREATE INDEX idx_attendance_audit_coach ON attendance_audit (coach_id, occurred_at DESC);

CREATE TRIGGER trg_attendance_audit_immutable
    BEFORE UPDATE OR DELETE ON attendance_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();
CREATE TRIGGER trg_attendance_audit_no_truncate
    BEFORE TRUNCATE ON attendance_audit
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_row_change();

ALTER TABLE consent_record     ENABLE ROW LEVEL SECURITY;
ALTER TABLE consent_revocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_audit   ENABLE ROW LEVEL SECURITY;
