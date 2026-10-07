-- Phase 1: tenant structure + auth. Business tables (plan, student, cycle...) come in later migrations.

CREATE TABLE organization (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Tenant root. organization_id is future-proofing only: it never grants access.
CREATE TABLE coach (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NULL REFERENCES organization (id),
    name              VARCHAR(200) NOT NULL,
    brand_name        VARCHAR(200) NOT NULL,
    logo_url          TEXT         NULL,
    primary_color     VARCHAR(7)   NULL,
    phone             VARCHAR(30)  NULL,
    timezone          VARCHAR(50)  NOT NULL DEFAULT 'America/Bogota',
    subscription_plan VARCHAR(30)  NOT NULL DEFAULT 'FREE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE coach_settings (
    coach_id            UUID PRIMARY KEY REFERENCES coach (id) ON DELETE CASCADE,
    cancel_window_hours INT         NOT NULL DEFAULT 2 CHECK (cancel_window_hours >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id             UUID         NOT NULL REFERENCES coach (id),
    email                VARCHAR(320) NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    role                 VARCHAR(20)  NOT NULL CHECK (role IN ('COACH', 'STUDENT')),
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Email is globally unique: login happens before the tenant is known.
CREATE UNIQUE INDEX uq_app_user_email ON app_user (lower(email));
CREATE INDEX idx_app_user_coach ON app_user (coach_id);

-- Supabase is used only as Postgres: enable RLS with no policies so the public API keys expose nothing.
-- The backend connects with a privileged role, which bypasses RLS.
ALTER TABLE organization   ENABLE ROW LEVEL SECURITY;
ALTER TABLE coach          ENABLE ROW LEVEL SECURITY;
ALTER TABLE coach_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user       ENABLE ROW LEVEL SECURITY;
