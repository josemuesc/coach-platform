-- Per-coach billing knobs. They have defaults now; an endpoint to change them comes in a later phase.
ALTER TABLE coach_settings
    ADD COLUMN expiring_soon_days    INT NOT NULL DEFAULT 5  CHECK (expiring_soon_days BETWEEN 0 AND 60),
    ADD COLUMN expiring_soon_classes INT NOT NULL DEFAULT 1  CHECK (expiring_soon_classes BETWEEN 0 AND 100),
    ADD COLUMN max_extension_days    INT NOT NULL DEFAULT 60 CHECK (max_extension_days BETWEEN 0 AND 365);
