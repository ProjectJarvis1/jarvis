-- 외부 쓰기(슬랙 보내기, 노션 쓰기) 시작 기록. 응답 저장 전에 서버가 꺼져도 같은 요청 키로 다시 실행하지 않게 한다.
CREATE TABLE IF NOT EXISTS external_dispatches (
    request_key text PRIMARY KEY,
    tool text NOT NULL,
    status text NOT NULL DEFAULT 'started',  -- started: 보냈을 수 있음, failed: 확실히 실패(다시 시도 가능)
    started_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
