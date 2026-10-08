-- Phase 2: plans, students, invitations, cycles, payments, cycle extensions.
-- Every table carries coach_id. Parents expose UNIQUE (id, coach_id) so children can use COMPOSITE foreign keys
-- (child_id, coach_id): the database itself rejects a row pointing at another tenant's parent.

CREATE TABLE plan (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id         UUID         NOT NULL REFERENCES coach (id),
    name             VARCHAR(100) NOT NULL,
    classes_included INT          NOT NULL CHECK (classes_included > 0),
    price_cop        BIGINT       NOT NULL CHECK (price_cop >= 0),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_plan_id_coach UNIQUE (id, coach_id)
);
CREATE UNIQUE INDEX uq_plan_coach_name ON plan (coach_id, lower(name));

CREATE TABLE student (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id           UUID         NOT NULL REFERENCES coach (id),
    full_name          VARCHAR(200) NOT NULL,
    email              VARCHAR(320) NOT NULL CHECK (email = lower(email)),
    whatsapp_phone     VARCHAR(30)  NULL,
    user_id            UUID         NULL UNIQUE REFERENCES app_user (id),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    whatsapp_opt_in_at TIMESTAMPTZ  NULL,
    data_consent_at    TIMESTAMPTZ  NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_student_id_coach UNIQUE (id, coach_id)
);
CREATE UNIQUE INDEX uq_student_coach_email ON student (coach_id, email);

-- Only the SHA-256 of the token is stored (hex, 64 chars). Single use: used_at is set atomically on accept.
CREATE TABLE invitation (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id   UUID        NOT NULL REFERENCES coach (id),
    student_id UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_by UUID        NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (student_id, coach_id) REFERENCES student (id, coach_id)
);
CREATE INDEX idx_invitation_student ON invitation (student_id);

CREATE TABLE cycle (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id          UUID        NOT NULL REFERENCES coach (id),
    student_id        UUID        NOT NULL,
    plan_id           UUID        NOT NULL,
    start_date        DATE        NOT NULL,
    end_date          DATE        NOT NULL,
    original_end_date DATE        NOT NULL,
    classes_included  INT         NOT NULL CHECK (classes_included > 0),
    classes_used      INT         NOT NULL DEFAULT 0,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'EXPIRED')),
    closed_at         TIMESTAMPTZ NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cycle_id_coach UNIQUE (id, coach_id),
    CONSTRAINT ck_cycle_used CHECK (classes_used BETWEEN 0 AND classes_included),
    CONSTRAINT ck_cycle_dates CHECK (end_date > start_date AND original_end_date > start_date
                                     AND end_date >= original_end_date),
    CONSTRAINT ck_cycle_closed CHECK ((status = 'ACTIVE') = (closed_at IS NULL)),
    FOREIGN KEY (student_id, coach_id) REFERENCES student (id, coach_id),
    FOREIGN KEY (plan_id, coach_id) REFERENCES plan (id, coach_id)
);
-- One active cycle per student, enforced by the database (the service validates too).
CREATE UNIQUE INDEX uq_cycle_one_active_per_student ON cycle (student_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_cycle_student_start ON cycle (student_id, start_date DESC);
CREATE INDEX idx_cycle_active_end ON cycle (end_date) WHERE status = 'ACTIVE';

CREATE TABLE payment (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id    UUID        NOT NULL REFERENCES coach (id),
    student_id  UUID        NOT NULL,
    cycle_id    UUID        NOT NULL UNIQUE,
    amount_cop  BIGINT      NOT NULL CHECK (amount_cop > 0),
    method      VARCHAR(20) NOT NULL CHECK (method IN ('NEQUI', 'TRANSFER', 'CASH')),
    paid_on     DATE        NOT NULL,
    recorded_by UUID        NOT NULL REFERENCES app_user (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (student_id, coach_id) REFERENCES student (id, coach_id),
    FOREIGN KEY (cycle_id, coach_id) REFERENCES cycle (id, coach_id)
);
CREATE INDEX idx_payment_student ON payment (student_id, paid_on DESC);

CREATE TABLE cycle_extension (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id          UUID         NOT NULL REFERENCES coach (id),
    cycle_id          UUID         NOT NULL,
    previous_end_date DATE         NOT NULL,
    new_end_date      DATE         NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    extended_by       UUID         NOT NULL REFERENCES app_user (id),
    extended_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_extension_dates CHECK (new_end_date > previous_end_date),
    FOREIGN KEY (cycle_id, coach_id) REFERENCES cycle (id, coach_id)
);
CREATE INDEX idx_cycle_extension_cycle ON cycle_extension (cycle_id);

ALTER TABLE plan            ENABLE ROW LEVEL SECURITY;
ALTER TABLE student         ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation      ENABLE ROW LEVEL SECURITY;
ALTER TABLE cycle           ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment         ENABLE ROW LEVEL SECURITY;
ALTER TABLE cycle_extension ENABLE ROW LEVEL SECURITY;
