-- Phase 3: weekly availability, blocks, class EVENTS (the coach's time slot, shared by several students when the
-- class is semi-personalized) and one ATTENDANCE per student per event. Same conventions as V2: coach_id everywhere,
-- composite FKs (child_id, coach_id), RLS enabled with no policies.
-- Existing plans and cycles (V2) become PERSONALIZED: that is what the DEFAULT below backfills.

CREATE EXTENSION IF NOT EXISTS btree_gist;   -- needed by the no-overlap exclusion constraint below

ALTER TABLE coach_settings
    ADD COLUMN class_duration_minutes INT NOT NULL DEFAULT 60 CHECK (class_duration_minutes BETWEEN 15 AND 180),
    ADD COLUMN default_group_capacity INT NOT NULL DEFAULT 4  CHECK (default_group_capacity BETWEEN 2 AND 10),
    ADD CONSTRAINT ck_cancel_window_range CHECK (cancel_window_hours BETWEEN 0 AND 48);

-- plan.modality: PERSONALIZED (1 student per event) or SEMI_PERSONALIZED (several). The default only backfills the
-- plans that already exist; it is dropped right after so that every NEW plan has to state its modality.
ALTER TABLE plan
    ADD COLUMN modality VARCHAR(20) NOT NULL DEFAULT 'PERSONALIZED' CHECK (modality IN ('PERSONALIZED', 'SEMI_PERSONALIZED'));
ALTER TABLE plan ALTER COLUMN modality DROP DEFAULT;

-- The cycle keeps the modality it was opened with (like classes_included), so editing a plan never changes a running cycle.
ALTER TABLE cycle
    ADD COLUMN modality VARCHAR(20) NOT NULL DEFAULT 'PERSONALIZED' CHECK (modality IN ('PERSONALIZED', 'SEMI_PERSONALIZED'));
UPDATE cycle c SET modality = p.modality FROM plan p WHERE p.id = c.plan_id AND p.coach_id = c.coach_id;
ALTER TABLE cycle ALTER COLUMN modality DROP DEFAULT;

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

-- The EVENT: a time slot of the coach. Modality and capacity come from the first student and are fixed when the event
-- is created (changing coach_settings.default_group_capacity later never touches existing events).
CREATE TABLE class_session (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id      UUID         NOT NULL REFERENCES coach (id),
    starts_at     TIMESTAMPTZ  NOT NULL,
    ends_at       TIMESTAMPTZ  NOT NULL,   -- fixed when the event is created: a later duration change never moves it
    modality      VARCHAR(20)  NOT NULL CHECK (modality IN ('PERSONALIZED', 'SEMI_PERSONALIZED')),
    capacity      INT          NOT NULL,
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    cancelled_by  UUID         NULL REFERENCES app_user (id),
    cancelled_at  TIMESTAMPTZ  NULL,
    cancel_reason VARCHAR(500) NULL,
    created_by    UUID         NOT NULL REFERENCES app_user (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_class_session_id_coach UNIQUE (id, coach_id),
    CONSTRAINT ck_class_session_times CHECK (ends_at > starts_at),
    CONSTRAINT ck_class_session_capacity CHECK
        ((modality = 'PERSONALIZED' AND capacity = 1) OR (modality = 'SEMI_PERSONALIZED' AND capacity BETWEEN 2 AND 10)),
    CONSTRAINT ck_class_session_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    -- A coach cannot have two overlapping SCHEDULED events, whatever their length (back-to-back is fine: ranges are [) ).
    -- Several students SHARE one event; they never create overlapping ones.
    CONSTRAINT ex_class_session_no_overlap EXCLUDE USING gist
        (coach_id WITH =, tstzrange(starts_at, ends_at) WITH &&) WHERE (status = 'SCHEDULED')
);
CREATE INDEX idx_class_session_coach_time ON class_session (coach_id, starts_at);

-- One student's place in an event. Cycle consumption, the cancellation window and marking attendance are all per attendance.
CREATE TABLE session_attendance (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id         UUID         NOT NULL REFERENCES coach (id),
    session_id       UUID         NOT NULL,
    student_id       UUID         NOT NULL,
    cycle_id         UUID         NOT NULL,
    status           VARCHAR(30)  NOT NULL CHECK (status IN
        ('SCHEDULED', 'ATTENDED', 'CANCELLED_ON_TIME', 'RESCHEDULED', 'NO_SHOW', 'CANCELLED_BY_COACH')),
    rescheduled_from UUID         NULL,
    cancelled_by     UUID         NULL REFERENCES app_user (id),
    cancelled_at     TIMESTAMPTZ  NULL,
    cancel_reason    VARCHAR(500) NULL,
    marked_by        UUID         NULL REFERENCES app_user (id),
    marked_at        TIMESTAMPTZ  NULL,
    override         BOOLEAN      NOT NULL DEFAULT FALSE,   -- the coach put the student here against the modality/capacity rules
    override_reason  VARCHAR(500) NULL,
    override_by      UUID         NULL REFERENCES app_user (id),
    created_by       UUID         NOT NULL REFERENCES app_user (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_session_attendance_id_coach UNIQUE (id, coach_id),
    CONSTRAINT ck_attendance_cancelled CHECK
        ((status IN ('CANCELLED_ON_TIME', 'RESCHEDULED', 'CANCELLED_BY_COACH')) = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_attendance_marked CHECK ((status IN ('ATTENDED', 'NO_SHOW')) = (marked_at IS NOT NULL)),
    CONSTRAINT ck_attendance_coach_reason CHECK
        (status <> 'CANCELLED_BY_COACH' OR (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0)),
    CONSTRAINT ck_attendance_override CHECK
        (NOT override OR (override_reason IS NOT NULL AND length(btrim(override_reason)) > 0 AND override_by IS NOT NULL)),
    FOREIGN KEY (session_id, coach_id) REFERENCES class_session (id, coach_id),
    FOREIGN KEY (student_id, coach_id) REFERENCES student (id, coach_id),
    FOREIGN KEY (cycle_id, coach_id) REFERENCES cycle (id, coach_id),
    FOREIGN KEY (rescheduled_from, coach_id) REFERENCES session_attendance (id, coach_id)
);
-- A student holds at most one live place in the same event (cancelled / rescheduled places do not count).
CREATE UNIQUE INDEX uq_attendance_live_per_event ON session_attendance (session_id, student_id)
    WHERE status IN ('SCHEDULED', 'ATTENDED', 'NO_SHOW');
CREATE INDEX idx_attendance_session ON session_attendance (session_id);
CREATE INDEX idx_attendance_student ON session_attendance (student_id, created_at DESC);
CREATE INDEX idx_attendance_cycle_status ON session_attendance (cycle_id, status);

ALTER TABLE availability_rule  ENABLE ROW LEVEL SECURITY;
ALTER TABLE availability_block ENABLE ROW LEVEL SECURITY;
ALTER TABLE class_session      ENABLE ROW LEVEL SECURITY;
ALTER TABLE session_attendance ENABLE ROW LEVEL SECURITY;
