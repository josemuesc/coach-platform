-- Phase 3: weekly availability, blocks (holidays...), class sessions, per-coach class duration.
-- Same conventions as V2: coach_id everywhere, composite FKs (child_id, coach_id), RLS enabled with no policies.

CREATE EXTENSION IF NOT EXISTS btree_gist;   -- needed by the no-overlap exclusion constraint below

ALTER TABLE coach_settings
    ADD COLUMN class_duration_minutes INT NOT NULL DEFAULT 60 CHECK (class_duration_minutes BETWEEN 15 AND 180),
    ADD CONSTRAINT ck_cancel_window_range CHECK (cancel_window_hours BETWEEN 0 AND 48);

-- A reopened cycle (an expired one the coach brings back to life) is audited in cycle_extension.
ALTER TABLE cycle_extension ADD COLUMN reopened BOOLEAN NOT NULL DEFAULT FALSE;

-- Weekly windows in the coach's local time (America/Bogota). Slots are cut from them every class_duration_minutes.
CREATE TABLE availability_rule (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id    UUID        NOT NULL REFERENCES coach (id),
    day_of_week INT         NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- ISO: 1 = Monday
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_availability_rule_times CHECK (end_time > start_time),
    CONSTRAINT uq_availability_rule UNIQUE (coach_id, day_of_week, start_time)
);

CREATE TABLE availability_block (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id   UUID         NOT NULL REFERENCES coach (id),
    starts_at  TIMESTAMPTZ  NOT NULL,
    ends_at    TIMESTAMPTZ  NOT NULL,
    reason     VARCHAR(200) NULL,
    created_by UUID         NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_availability_block_times CHECK (ends_at > starts_at)
);
CREATE INDEX idx_availability_block_range ON availability_block (coach_id, starts_at);

CREATE TABLE class_session (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id         UUID         NOT NULL REFERENCES coach (id),
    student_id       UUID         NOT NULL,
    cycle_id         UUID         NOT NULL,
    starts_at        TIMESTAMPTZ  NOT NULL,
    ends_at          TIMESTAMPTZ  NOT NULL,   -- fixed when the class is booked: a later duration change never moves it
    status           VARCHAR(30)  NOT NULL CHECK (status IN
        ('SCHEDULED', 'ATTENDED', 'CANCELLED_ON_TIME', 'RESCHEDULED', 'NO_SHOW', 'CANCELLED_BY_COACH')),
    rescheduled_from UUID         NULL,
    cancelled_by     UUID         NULL REFERENCES app_user (id),
    cancelled_at     TIMESTAMPTZ  NULL,
    cancel_reason    VARCHAR(500) NULL,
    marked_by        UUID         NULL REFERENCES app_user (id),
    marked_at        TIMESTAMPTZ  NULL,
    created_by       UUID         NOT NULL REFERENCES app_user (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_class_session_id_coach UNIQUE (id, coach_id),
    CONSTRAINT ck_class_session_times CHECK (ends_at > starts_at),
    CONSTRAINT ck_class_session_cancelled CHECK
        ((status IN ('CANCELLED_ON_TIME', 'RESCHEDULED', 'CANCELLED_BY_COACH')) = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_class_session_marked CHECK
        ((status IN ('ATTENDED', 'NO_SHOW')) = (marked_at IS NOT NULL)),
    CONSTRAINT ck_class_session_coach_reason CHECK
        (status <> 'CANCELLED_BY_COACH' OR (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0)),
    FOREIGN KEY (student_id, coach_id) REFERENCES student (id, coach_id),
    FOREIGN KEY (cycle_id, coach_id) REFERENCES cycle (id, coach_id),
    FOREIGN KEY (rescheduled_from, coach_id) REFERENCES class_session (id, coach_id),
    -- A coach cannot have two overlapping SCHEDULED classes, whatever their length (back-to-back is fine: ranges are [) ).
    CONSTRAINT ex_class_session_no_overlap EXCLUDE USING gist
        (coach_id WITH =, tstzrange(starts_at, ends_at) WITH &&) WHERE (status = 'SCHEDULED')
);
CREATE INDEX idx_class_session_student ON class_session (student_id, starts_at DESC);
CREATE INDEX idx_class_session_coach_time ON class_session (coach_id, starts_at);
CREATE INDEX idx_class_session_cycle_status ON class_session (cycle_id, status);

ALTER TABLE availability_rule  ENABLE ROW LEVEL SECURITY;
ALTER TABLE availability_block ENABLE ROW LEVEL SECURITY;
ALTER TABLE class_session      ENABLE ROW LEVEL SECURITY;
