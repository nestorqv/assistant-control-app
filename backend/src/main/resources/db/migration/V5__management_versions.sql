-- Stage 4b: optimistic concurrency for admin edits. The writer locks the row and compares the version the
-- admin loaded; JPA @Version increments it. Credential, lock and consent changes do not touch it.
ALTER TABLE employee ADD COLUMN version integer NOT NULL DEFAULT 0;
ALTER TABLE branch   ADD COLUMN version integer NOT NULL DEFAULT 0;

-- Names are trimmed by the app; the database refuses blank ones too (company already has this check).
ALTER TABLE employee ADD CONSTRAINT ck_employee_name CHECK (btrim(name) <> '');
ALTER TABLE branch   ADD CONSTRAINT ck_branch_name   CHECK (btrim(name) <> '');

-- Optional reason of an admin action (4b: PIN reset only). Code is language-neutral; the note is short
-- free text that the app keeps free of PINs and other digit runs. Never a PIN, a name or a code.
ALTER TABLE audit_log ADD COLUMN reason_code varchar(30)  NULL;
ALTER TABLE audit_log ADD COLUMN reason_note varchar(200) NULL;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_reason_code
    CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z_]{3,30}$');
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_reason_note
    CHECK (reason_note IS NULL OR (reason_code IS NOT NULL AND btrim(reason_note) <> ''));
