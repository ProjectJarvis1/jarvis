-- 비서 우선순위 1: 앱 오류 수집, 사용자별 AI 사용량, 얼굴 저장 동의 시각.
-- 번호를 띄운 것은 다른 브랜치의 마이그레이션(006~)과 순서가 겹치지 않게 하려는 것이다.

-- 앱이 강제 종료됐을 때의 기록. 대화 내용이나 개인 기록은 넣지 않는다(스택, 앱·기기 정보만).
CREATE TABLE IF NOT EXISTS client_errors (
    id          bigserial PRIMARY KEY,
    user_id     text NOT NULL DEFAULT current_setting('app.user_id', true),
    happened_at timestamptz,
    received_at timestamptz NOT NULL DEFAULT now(),
    app_version text NOT NULL DEFAULT '',
    flavor      text NOT NULL DEFAULT '',
    device      text NOT NULL DEFAULT '',
    os_version  text NOT NULL DEFAULT '',
    stack       text NOT NULL
);
CREATE INDEX IF NOT EXISTS client_errors_user_day_idx ON client_errors(user_id, received_at);

-- 사용자별, 한국 날짜별 AI 사용량. kind 마다 한 줄.
CREATE TABLE IF NOT EXISTS ai_usage (
    user_id  text NOT NULL DEFAULT current_setting('app.user_id', true),
    day      date NOT NULL,
    kind     text NOT NULL,
    amount   bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day, kind)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['client_errors', 'ai_usage']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS own_rows ON %I', t);
        EXECUTE format($p$CREATE POLICY own_rows ON %I USING (user_id = current_setting('app.user_id', true))
                          WITH CHECK (user_id = current_setting('app.user_id', true)
                                      AND EXISTS (SELECT 1 FROM app_users u WHERE u.id = user_id))$p$, t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON client_errors, ai_usage TO jarvis_app;
GRANT USAGE, SELECT ON SEQUENCE client_errors_id_seq TO jarvis_app;

-- 얼굴은 생체 정보라 찍힌 사람의 동의가 필요하다(D37). 사용자가 동의를 확인한 시각을 남긴다.
-- 이 열이 생기기 전에 저장된 얼굴은 NULL 이다.
ALTER TABLE person_faces ADD COLUMN IF NOT EXISTS consent_at timestamptz;
