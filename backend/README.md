> 통합 저장소에서는 `cd backend/jarvis-server` 후 실행합니다.
> 먼저 `.env.example`을 `.env`로 복사해 `POSTGRES_PASSWORD`, `DATABASE_URL`,
> `API_KEY`를 설정하세요. 예제의 `replace-with-*`는 실제 임의 값으로 바꿔야 합니다.
> DB 비밀번호와 URL의 비밀번호를 일치시키세요. 기존 DB 볼륨 비밀번호는 자동 변경되지 않습니다.
> 이 아래 명령은 `jarvis-server` 디렉터리를 기준으로 합니다.

# Jarvis Memory Server

자비스 OS의 기억 저장·검색 백엔드입니다. Android 클라이언트가 Gemini Live
function calling으로 명령을 판단한 뒤 이 FastAPI 서버를 호출합니다.

```text
JarvisAndroidClient -> Jarvis Memory API -> PostgreSQL + pgvector
```

## 제공 기능

- 사람, 회의, 니즈, 일상 기억 저장 및 CRUD
- JSONB 메타데이터와 이미지 경로 저장
- KURE-v1 기본 임베딩 및 pgvector 유사도 검색
- 이름/조직/별칭 검색과 얼굴 임베딩 검색
- 회의 전사·요약 결과 저장
- `/mcp/tools` 기반 도구 목록 및 에이전트 연동
- 선택적 Google, Slack, Notion OAuth 연동

## 실행

자세한 설치, 환경변수, API 목록은 [`jarvis-server/README.md`](jarvis-server/README.md)를
참고하세요.

```bash
cd backend/jarvis-server
docker compose up -d
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
