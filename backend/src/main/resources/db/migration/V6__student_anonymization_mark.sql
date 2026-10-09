-- Revoking a DATA consent suspends the account (student.active / app_user.active = false) and marks the student for
-- anonymization. The mark is a date, never cleared by the application: the anonymization itself is a later step.
-- Consent records and the attendance audit are kept untouched.
ALTER TABLE student ADD COLUMN anonymization_requested_at TIMESTAMPTZ NULL;
