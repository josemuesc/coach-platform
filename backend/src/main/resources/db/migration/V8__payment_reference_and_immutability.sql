-- Payments: how it was received gets an "OTHER", an optional receipt reference is added, and the table becomes append-only.
-- A payment is accounting evidence (amount, method, reference, who and when): it is never edited or deleted by the application, nor by
-- anyone by accident. A wrongly recorded payment is corrected by hand, by the migrations owner, following docs/payment-correction.md.

ALTER TABLE payment DROP CONSTRAINT payment_method_check;
ALTER TABLE payment ADD CONSTRAINT ck_payment_method CHECK (method IN ('NEQUI', 'TRANSFER', 'CASH', 'OTHER'));

-- The receipt number. Trimmed, one line, no control characters and never a run of 12+ digits (a possible card or account number);
-- the application enforces the same rules and explains them, this is the last line of defence.
ALTER TABLE payment ADD COLUMN reference VARCHAR(100) NULL;
ALTER TABLE payment ADD CONSTRAINT ck_payment_reference CHECK (
    reference IS NULL
    OR (length(btrim(reference)) > 0
        AND reference = btrim(reference)
        AND reference !~ '[[:cntrl:]]'
        AND reference !~ '[0-9]{12,}'));

CREATE TRIGGER trg_payment_immutable
    BEFORE UPDATE OR DELETE ON payment
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();
CREATE TRIGGER trg_payment_no_truncate
    BEFORE TRUNCATE ON payment
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_row_change();
