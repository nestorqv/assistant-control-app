-- Stage 2: check-in rules. work_date is the company-local date the shift belongs to
-- (computed in the app from company.time_zone; a generated column cannot read another table).
ALTER TABLE time_entry
    ADD COLUMN work_date            date         NULL,
    ADD COLUMN skew_seconds         integer      NULL,
    ADD COLUMN late_minutes         integer      NULL,
    ADD COLUMN gps_accuracy_meters  integer      NULL,
    ADD COLUMN review_reasons       varchar(200) NULL;

-- Backfill (dev rows only, if any) then enforce.
UPDATE time_entry te SET work_date = (te.server_time AT TIME ZONE c.time_zone)::date
  FROM company c WHERE c.id = te.company_id AND te.work_date IS NULL;
ALTER TABLE time_entry ALTER COLUMN work_date SET NOT NULL;

ALTER TABLE time_entry
    ADD CONSTRAINT ck_time_entry_location_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    ADD CONSTRAINT ck_time_entry_late_minutes CHECK (late_minutes IS NULL OR (type = 'CLOCK_IN' AND late_minutes >= 0)),
    ADD CONSTRAINT ck_time_entry_gps_accuracy CHECK (gps_accuracy_meters >= 0),
    ADD CONSTRAINT ck_time_entry_review_reasons CHECK (review_reasons ~ '^[A-Z_]+(,[A-Z_]+)*$');

-- One CLOCK_IN and one CLOCK_OUT per employee per work_date (backstop for the app-level lock).
CREATE UNIQUE INDEX uq_time_entry_employee_work_date_type ON time_entry (employee_id, work_date, type);
-- Stage 4 dashboard: one company, one day.
CREATE INDEX ix_time_entry_company_work_date ON time_entry (company_id, work_date);

ALTER TABLE evidence
    ADD CONSTRAINT uq_evidence_time_entry_type UNIQUE (time_entry_id, type),
    -- Only PHOTO rows carry a key; a PHOTO key may be NULL after a Stage 5 retention purge.
    ADD CONSTRAINT ck_evidence_storage_key_type CHECK (type = 'PHOTO' OR storage_key IS NULL),
    ADD CONSTRAINT ck_evidence_storage_key_format
        CHECK (storage_key ~ '^[0-9]+/[0-9]{4}-[0-9]{2}/[0-9a-f-]{36}\.jpg$'),
    ADD CONSTRAINT ck_evidence_storage_key_company
        CHECK (storage_key IS NULL OR split_part(storage_key, '/', 1) = company_id::text);
