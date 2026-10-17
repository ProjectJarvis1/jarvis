-- Non-destructive upgrade: stable optimistic revisions for all write paths.
ALTER TABLE people ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1;
ALTER TABLE meetings ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1;
ALTER TABLE meetings ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE memories ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE OR REPLACE FUNCTION jarvis_advance_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.revision = OLD.revision + 1;
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;
CREATE TRIGGER people_revision BEFORE UPDATE ON people
    FOR EACH ROW EXECUTE FUNCTION jarvis_advance_revision();
CREATE TRIGGER meetings_revision BEFORE UPDATE ON meetings
    FOR EACH ROW EXECUTE FUNCTION jarvis_advance_revision();
CREATE TRIGGER memories_revision BEFORE UPDATE ON memories
    FOR EACH ROW EXECUTE FUNCTION jarvis_advance_revision();

CREATE INDEX IF NOT EXISTS memories_meeting_idx ON memories(related_meeting_id);
CREATE INDEX IF NOT EXISTS needs_meeting_idx ON needs(meeting_id);
CREATE INDEX IF NOT EXISTS request_receipts_created_idx ON request_receipts(created_at);
CREATE INDEX IF NOT EXISTS assistant_changes_record_idx ON assistant_changes(kind, record_id, created_at DESC);
ALTER TABLE assistant_changes ADD CONSTRAINT assistant_changes_kind_check
    CHECK (kind IN ('memory', 'person', 'meeting'));
ALTER TABLE assistant_changes ADD CONSTRAINT assistant_changes_state_check
    CHECK (state IN ('preview', 'applied', 'undone'));
ALTER TABLE request_receipts ADD CONSTRAINT request_receipts_status_check
    CHECK (status BETWEEN 200 AND 299);
