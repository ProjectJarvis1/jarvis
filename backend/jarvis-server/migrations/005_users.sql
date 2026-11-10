-- Per-user data. Each request sets app.user_id (app/db.py get_conn); row level security
-- then limits every read and write to that user's rows, so router SQL needs no user filter.
-- Existing rows belong to "default", which ADMIN_EMAIL's Google account maps to on login.

CREATE TABLE IF NOT EXISTS app_users (
    id          text PRIMARY KEY,
    google_sub  text UNIQUE,
    email       text NOT NULL DEFAULT '',
    name        text NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app_sessions (
    token_hash  text PRIMARY KEY,
    user_id     text NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS app_sessions_user_idx ON app_sessions(user_id);

INSERT INTO app_users(id, email, name) VALUES ('default', '', '기존 사용자') ON CONFLICT (id) DO NOTHING;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['people', 'meetings', 'memories', 'entities', 'memory_entities', 'needs',
                             'proposal_points', 'person_faces', 'agent_tool_calls', 'assistant_changes']
    LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS user_id text NOT NULL DEFAULT %L', t, 'default');
    END LOOP;
    -- New rows take the requesting user; a request without one cannot insert (NOT NULL).
    FOREACH t IN ARRAY ARRAY['people', 'meetings', 'memories', 'entities', 'memory_entities', 'needs',
                             'proposal_points', 'person_faces', 'agent_tool_calls', 'assistant_changes',
                             'agent_runs', 'pending_actions']
    LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN user_id SET DEFAULT current_setting(%L, true)', t, 'app.user_id');
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I(user_id)', t || '_user_idx', t);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        -- The app connects as the table owner, which skips policies unless forced.
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS own_rows ON %I', t);
        -- Writes also need the user to still exist: a save still running when its account is deleted
        -- must not recreate data under the deleted id.
        EXECUTE format($p$CREATE POLICY own_rows ON %I USING (user_id = current_setting('app.user_id', true))
                          WITH CHECK (user_id = current_setting('app.user_id', true)
                                      AND EXISTS (SELECT 1 FROM app_users u WHERE u.id = user_id))$p$, t);
    END LOOP;
END $$;

-- Two users may each know a "홍길동".
ALTER TABLE entities DROP CONSTRAINT IF EXISTS entities_entity_type_label_key;
CREATE UNIQUE INDEX IF NOT EXISTS entities_user_type_label_key ON entities(user_id, entity_type, label);

-- Superusers and roles with BYPASSRLS ignore every policy, and the database owner usually is one.
-- Requests therefore run as this plain role (app/db.py switches to it on each checkout).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jarvis_app') THEN
        CREATE ROLE jarvis_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END $$;
GRANT jarvis_app TO CURRENT_USER;
GRANT USAGE ON SCHEMA public TO jarvis_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jarvis_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO jarvis_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jarvis_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO jarvis_app;
