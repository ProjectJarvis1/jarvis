# Jarvis

**보고, 대화하고, 기억을 다시 찾아주는 스마트 글라스 AI 어시스턴트.**

Jarvis는 스마트 글라스나 스마트폰으로 보고 있는 장면을 이해하고, 대화 중 필요한 정보를 저장하며, 나중에 자연어로 다시 찾을 수 있도록 만드는 개인 AI 서비스입니다. 음성 대화, 카메라, 사람과 회의 기록, 의미 기반 검색을 하나의 흐름으로 연결합니다.

“이거 기억해줘”, “지난번 미팅에서 어떤 얘기를 했지?”, “아까 찍은 사진 다시 설명해줘”처럼 말하면, AI가 필요한 도구를 선택하고 앱과 서버가 실제 작업을 수행합니다. 다른 질문을 하더라도 이전 작업의 상태와 결과를 따로 관리해 대화의 흐름을 이어가는 것이 목표입니다.

이 저장소는 Android 중심의 클라이언트와 FastAPI 백엔드를 함께 관리하는 모노레포입니다. 현재 개발 단계이며, 아래 기능은 코드에 구현된 범위를 설명합니다. 기기·모델·권한·서버 설정에 따라 실제 동작이 달라질 수 있습니다.

## 목차

- [주요 기능](#주요-기능)
- [사용 시나리오](#사용-시나리오)
- [아키텍처](#아키텍처)
- [실시간 대화와 작업 관리](#실시간-대화와-작업-관리)
- [저장소 구성](#저장소-구성)
- [빠른 시작](#빠른-시작)
- [앱 사용 방법](#앱-사용-방법)
- [API와 데이터 구조](#api와-데이터-구조)
- [개발 및 검증](#개발-및-검증)
- [문제 해결](#문제-해결)
- [데이터와 운영 설정](#데이터와-운영-설정)
- [관련 문서와 라이선스](#관련-문서와-라이선스)

## 주요 기능

| 기능 | 설명 | 사용 조건 및 범위 |
| --- | --- | --- |
| 실시간 음성 대화 | Gemini Live로 음성을 주고받고, 응답 중 새 발화에 따라 재생을 중단하는 대화 흐름 | Gemini 키, 네트워크, 마이크 권한 필요 |
| 현재 시야 이해 | 요청 시 카메라 이미지 한 장을 가져와 장면이나 문서를 설명 | 글라스 또는 폰 카메라 사용 |
| 일상 기억 저장 | 사용자 메모, AI의 장면 해석, 사진과 발생 시각을 함께 저장 | Jarvis Memory API 연결 필요 |
| 기억 검색 | 문장 의미를 기준으로 과거 기억·사람·회의를 검색 | PostgreSQL + pgvector, 임베딩 모델 사용 |
| 사람과 관계 기록 | 이름·조직·역할 등 사람 정보를 저장하고 관련 회의·니즈를 연결 | 백엔드에는 얼굴 식별 및 다중 얼굴 등록 API도 포함 |
| 회의 기록 | 녹음 파일을 업로드하고 전사·요약 결과를 저장 | 서버의 OpenAI 키 설정 필요. 회의 녹음과 Live 대화는 현재 동시에 사용하지 않음 |
| 작업 상태와 결과 보관 | 작업별 ID와 버전으로 진행 상태를 관리하고 이전 결과를 다시 조회 | 앱 로컬 저장소 사용. 앱 종료 후 작업을 계속 실행하는 서비스는 아님 |
| 휴대폰 작업 | 연락처 검색, 전화·문자, 연락처 생성, 일정 생성 도구 | 해당 Android 권한 및 확인 절차 필요 |
| 확장 API | 서버 에이전트, Flash 호출 프록시, MCP 도구, Google·Slack·Notion 연동 경로 | 별도 키·OAuth 설정 필요. 모든 경로가 기본 앱 흐름에 연결된 것은 아님 |

실시간 대화 모드가 기본이며, 기존 Android STT 및 Whisper 기반 입력 경로도 유지합니다. iOS 클라이언트와 스트리밍 보조 서버도 포함하지만, 이 문서의 설치 및 검증 기준은 Android입니다.

## 사용 시나리오

### 눈앞의 장면을 기억하기

1. 글라스나 폰 카메라로 장면을 봅니다.
2. “이거 기억해줘. 오늘 본 전시 작품이야”라고 말합니다.
3. 앱이 장면을 촬영하고 AI가 이미지 내용을 해석합니다.
4. 사용자 메모와 해석, 이미지가 서버에 저장됩니다.
5. 나중에 “오늘 전시에서 본 작품 찾아줘”처럼 검색합니다.

저장되는 사진은 해당 촬영 작업의 결과에 연결됩니다. 저장 단계에서 무조건 다시 촬영해 다른 장면을 저장하지 않도록 원본 작업 ID와 버전을 확인합니다.

### 대화 중 다른 작업으로 전환하기

```text
사용자: 지금 보는 거 찍어서 설명해줘.
사용자: 잠깐, 지난번 배터리 미팅부터 찾아줘.
Jarvis: 새 요청에 맞춰 검색을 처리하고 이전 촬영 작업은 별도로 관리합니다.
사용자: 아까 사진은 어떻게 됐어?
Jarvis: 보관된 작업 상태와 결과를 조회해 이어서 답합니다.
```

위 내용은 동작 흐름을 설명하는 예시입니다. 실제 답변 문구와 도구 선택은 모델 판단에 따라 달라집니다. 촬영·검색처럼 독립적인 작업은 함께 진행할 수 있지만, 동일한 카메라를 쓰는 작업은 순서대로 실행합니다.

### 사람과 미팅 맥락 찾기

- “박부장 관련 기록 찾아줘.”
- “지난번 배터리 부품사 미팅에서 나온 니즈 알려줘.”
- “이 사람에게 어떤 제안을 하면 좋을지 알려줘.”

사람·회의·니즈·기억 데이터를 연결해 검색하고, 조회된 맥락을 바탕으로 AI가 답변합니다. 아직 저장하지 않은 개인 기록까지 알고 있는 것은 아닙니다.

## 아키텍처

```mermaid
flowchart LR
    User[사용자] --> Glass[스마트 글라스 / 폰 카메라]
    User --> Audio[음성 입력]
    Glass --> Android[Android 앱]
    Audio --> Android
    Android <-->|실시간 음성 · 필요한 이미지| Live[Gemini Live]
    Live -->|도구 호출| Router[ToolCallRouter]
    Router --> Tasks[TaskManager]
    Tasks --> Local[카메라 · 연락처 · 일정]
    Tasks --> Cache[앱 로컬 작업 · 결과 캐시]
    Tasks -->|HTTP| API[Jarvis Memory API]
    API --> DB[(PostgreSQL + pgvector)]
    API --> Images[이미지 파일 저장소]
    API --> Embedding[텍스트 임베딩 · 얼굴 처리]
    API -. 회의 전사 · 요약 .-> AI[외부 AI API]
```

### 각 구성 요소의 역할

| 구성 요소 | 역할 | 주요 코드 |
| --- | --- | --- |
| Android UI·기기 연결 | 글라스 연결, 폰 카메라, 설정, 기록 화면 | `frontend/samples/CameraAccessAndroid/app/` |
| Live 세션 | WebSocket 연결, 음성 송수신, 이미지 전송, 응답 중단 | `gemini/GeminiLiveService.kt`, `gemini/GeminiSessionViewModel.kt` |
| 도구 라우터 | 모델이 요청한 도구를 로컬 작업 또는 서버 요청으로 연결 | `openclaw/ToolCallRouter.kt` |
| 작업 관리자 | 실행 상태, 취소·보류, 결과 저장, 자원별 실행 순서 관리 | `tasks/TaskManager.kt` |
| HTTP 브리지 | 메모리 API 호출 및 응답 처리 | `openclaw/OpenClawBridge.kt` |
| 백엔드 | 사람·회의·기억 관리, 검색, 에이전트 및 연동 API | `backend/jarvis-server/app/` |
| 영속 데이터 | 관계형 데이터, JSONB 메타데이터, 벡터 검색 | `backend/jarvis-server/schema.sql` |

Android 코드 경로는 `app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/` 아래를 기준으로 합니다. `OpenClaw`라는 이름이 일부 클래스에 남아 있지만, 기본 기억 저장·검색 경로는 Jarvis Memory API를 직접 호출합니다.

현재 Android 기본 경로에서는 **Gemini Live의 도구 호출을 앱이 실행**합니다. 서버에는 별도의 `/agent/*` 및 Flash 프록시 API가 있지만, 모든 대화가 독립적인 서버 Flash 에이전트를 거친다고 가정하면 안 됩니다.

### 주요 기술

| 영역 | 구성 |
| --- | --- |
| Android | Kotlin, Jetpack Compose, CameraX, Meta Wearables DAT, OkHttp |
| 음성 | Gemini Live, Android STT, Whisper JNI |
| 백엔드 | Python, FastAPI, Pydantic, Uvicorn |
| 데이터베이스 | PostgreSQL 16, pgvector, JSONB |
| 검색 | sentence-transformers, 기본 `nlpai-lab/KURE-v1` 텍스트 임베딩 |
| 얼굴 처리 | InsightFace, ONNX Runtime 및 기존 Android 보조 앱 |
| 선택적 확장 | OpenAI API, Gemini Flash 프록시, LangGraph, OAuth, MCP 도구 |

버전은 각 프로젝트의 Gradle 설정 및 `requirements.txt`를 기준으로 확인합니다.

## 실시간 대화와 작업 관리

대화에서 지금 응답할 요청과, 실제 기기·서버에서 실행 중인 작업을 분리합니다.

- **작업 ID:** 각 도구 작업에 별도 식별자를 부여합니다.
- **작업 버전:** 취소·보류·재개 등 상태 변경 후 늦게 도착한 이전 결과를 구분합니다.
- **자원별 실행:** 독립적인 읽기·검색 작업은 병렬 실행할 수 있고, 카메라 같은 공유 자원은 직렬화합니다.
- **결과 보관:** 다른 주제로 넘어간 작업의 결과는 보관하고, 다시 요청하면 조회합니다.
- **부작용 구분:** 이미 서버에 전달된 저장·발송 작업은 취소해도 실제 반영 여부가 불확실할 수 있으므로 자동 재실행하지 않습니다.
- **발화 우선:** 과거 작업의 완료 응답이 현재 대화에 끼어들지 않도록 현재 작업과 음성 상태를 확인합니다.

작업 상태에는 `QUEUED`, `RUNNING`, `WAITING_USER`, `HELD`, `COMPLETED`, `FAILED`, `CANCELLED`, `OUTCOME_UNKNOWN`이 있습니다. 마지막 상태는 요청이 외부로 전달됐지만 결과를 확정하지 못한 경우를 뜻합니다.

작업 기록과 촬영 결과는 앱 전용 저장소에 보관하며, 보관 기간과 용량 제한을 적용합니다. 앱이 종료된 뒤에도 실행을 계속하는 백그라운드 워커나 서버 차원의 정확히 한 번 실행 보장은 구현 범위에 포함되지 않습니다.

구체적인 상태 전이·예외 처리·테스트 범위는 [작업 관리 변경사항](frontend/samples/CameraAccessAndroid/docs/2026-09-19-task-management-changes.md)을 참고하세요.

## 저장소 구성

```text
Jarvis/
├── frontend/
│   ├── samples/
│   │   ├── CameraAccessAndroid/   # 주 Android 클라이언트
│   │   └── CameraAccess/          # iOS 클라이언트 및 기존 보조 서버
│   └── assets/                   # 이미지 등 프로젝트 자료
├── backend/
│   ├── jarvis-server/
│   │   ├── app/                  # FastAPI 및 도메인 로직
│   │   ├── scripts/              # 시드 데이터 · 검색 보정 도구
│   │   ├── schema.sql
│   │   ├── docker-compose.yml    # PostgreSQL + pgvector
│   │   └── .env.example
│   ├── streaming-server/         # 스트리밍 보조 서버
│   └── app/                      # 원본의 얼굴 인식 Android 보조 앱
├── docs/                         # 저장소 통합 기록
├── LICENSE
└── NOTICE
```

`backend/app/`은 FastAPI 소스가 아니라 기존 얼굴 인식 Android 보조 앱입니다. 메모리 API를 수정하려면 `backend/jarvis-server/app/`을 사용하세요.

## 빠른 시작

### 1. 준비

- Git, Docker Compose, Python 3.11 이상
- Android Studio 및 프로젝트가 요구하는 Android SDK·NDK·CMake
- Android 12(API 31) 이상 기기
- Gemini API 키
- Meta DAT Android 패키지를 다운로드할 수 있는 GitHub 계정과 패키지 읽기 권한 토큰
- 선택 사항: 지원되는 Meta 스마트 글라스, 회의 전사·요약용 OpenAI 키

글라스 없이도 폰 카메라 모드로 시작할 수 있습니다. 첫 Python 의존성 설치와 임베딩·얼굴 모델 다운로드에는 시간과 디스크 공간이 필요합니다.

```bash
git clone https://github.com/ProjectJarvis1/jarvis.git
cd jarvis
```

### 2. 백엔드 환경변수 설정

저장소 루트에서 실행합니다.

```bash
cd backend/jarvis-server
cp .env.example .env

# 서로 다른 임의 값을 생성해 DB 비밀번호와 API 키에 사용합니다.
openssl rand -hex 32
openssl rand -hex 32
```

`.env`의 다음 항목을 수정합니다. 아래 값은 예시 자리표시자이며 그대로 실행하는 값이 아닙니다.

```dotenv
POSTGRES_PASSWORD=replace-with-random-password
DATABASE_URL=postgresql://jarvis:replace-with-random-password@localhost:5433/jarvis
API_KEY=replace-with-random-api-key

EMBEDDING_MODEL=nlpai-lab/KURE-v1
EMBEDDING_DIM=1024
EMBEDDING_DEVICE=cpu
```

`DATABASE_URL`의 비밀번호는 `POSTGRES_PASSWORD`와 같아야 합니다. `openssl rand -hex` 결과를 사용하면 URL 특수문자 인코딩 문제도 피할 수 있습니다. API 키는 앱에서 사용할 `jarvisApiKey`와 동일하게 설정합니다.

| 환경변수 | 용도 | 설정 시점 |
| --- | --- | --- |
| `POSTGRES_PASSWORD` | Compose DB 초기 비밀번호 | 필수 |
| `DATABASE_URL` | API의 PostgreSQL 연결 문자열 | 필수 |
| `API_KEY` | 메모리 API 요청 인증 | 필수 |
| `EMBEDDING_MODEL`, `EMBEDDING_DIM` | 검색 모델과 벡터 차원 | 기본값 사용 가능 |
| `EMBEDDING_DEVICE` | `cpu`, `cuda`, `mps` 중 실행 환경 | 환경에 맞게 설정 |
| `OPENAI_API_KEY` | 회의 전사·요약 및 관련 서버 AI 기능 | 해당 기능 사용 시 |
| `GEMINI_API_KEY` | 서버 Flash 프록시 호출 | 해당 경로 사용 시 `.env`에 추가 |
| `GOOGLE_*`, `SLACK_*`, `NOTION_*` | 외부 서비스 OAuth 설정 | 해당 연동 사용 시 |

기존 DB 볼륨의 비밀번호는 `.env`를 수정하는 것만으로 바뀌지 않습니다. 기존 데이터를 쓰는 경우 실제 DB 계정과 설정을 일치시키세요.

### 3. DB와 API 실행

같은 `backend/jarvis-server` 디렉터리에서 실행합니다.

```bash
docker compose up -d

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 폰에서 접근할 수 있도록 개발 서버를 실행합니다.
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Compose는 DB를 실행합니다. Python API는 위 Uvicorn 명령으로 별도 실행합니다. DB 포트 `5433`은 기본적으로 서버의 localhost에만 바인딩됩니다.

- 상태 확인: `http://localhost:8000/health`
- API 명세 및 요청 테스트: `http://localhost:8000/docs`

`/health`는 서버와 설정 확인용이며, 모든 외부 AI 기능의 정상 작동을 보장하는 검사는 아닙니다. 검색용 예제 데이터가 필요하면 별도 터미널에서 가상환경을 활성화하고 `python scripts/seed.py`를 실행할 수 있습니다. 이 명령은 DB에 예제 데이터를 기록합니다.

### 4. Android 프로젝트 설정

Android Studio에서 다음 디렉터리를 엽니다.

```text
frontend/samples/CameraAccessAndroid
```

프로젝트의 `local.properties`에 패키지 다운로드 계정을 설정합니다. Android Studio가 만든 `sdk.dir`은 유지합니다.

```properties
github_username=YOUR_GITHUB_USERNAME
github_token=YOUR_GITHUB_PACKAGES_TOKEN
```

다음 명령은 **저장소 루트**에서 실행합니다.

```bash
cd frontend/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess
cp Secrets.kt.example Secrets.kt
```

복사한 파일에서 다음 세 값을 설정하고 나머지 필드는 유지합니다.

```kotlin
const val geminiAPIKey = "YOUR_GEMINI_API_KEY"
const val jarvisApiBase = "http://YOUR_SERVER_HOST:8000"
const val jarvisApiKey = "YOUR_JARVIS_API_KEY"
```

- `jarvisApiKey`는 백엔드 `.env`의 `API_KEY`와 같아야 합니다.
- 실제 폰에서는 `localhost`가 폰 자신을 가리킵니다. 폰에서 접근 가능한 서버 호스트 이름이나 주소를 사용하세요.
- 같은 Wi-Fi를 사용하고 서버 방화벽에서 개발 API 포트에 접근할 수 있어야 합니다.
- 앱 Settings에서 이미 값을 저장했다면 저장된 설정도 확인하세요.

`Secrets.kt`, `local.properties`, `.env`, 서명키는 Git 추적 대상에서 제외합니다.

### 5. 빌드와 실행

Android Studio에서 Gradle Sync 후 `app`을 실행하거나, 저장소 루트에서 다음 명령을 사용합니다.

```bash
cd frontend/samples/CameraAccessAndroid
./gradlew :app:assembleDebug
```

APK 위치:

```text
frontend/samples/CameraAccessAndroid/app/build/outputs/apk/debug/app-debug.apk
```

현재 주 Android 클라이언트는 DAT SDK `0.5.0`, compile SDK `35` 설정을 사용합니다. 디버그 빌드는 개발 PC의 기본 디버그 서명키를 사용하므로 다른 서명키로 설치한 기존 앱에 업데이트 설치가 되지 않을 수 있습니다.

## 앱 사용 방법

1. 앱을 실행하고 카메라·마이크 등 필요한 권한을 허용합니다.
2. Settings에서 Gemini 키와 Jarvis 서버 주소·API 키를 확인합니다.
3. 우선 **Start on Phone**으로 폰 카메라를 시작합니다. 글라스를 사용한다면 기기 연결과 개발 설정을 완료한 뒤 **Start Streaming**을 사용합니다.
4. AI 버튼으로 대화를 시작합니다. **실시간 대화** 설정은 기본으로 켜져 있습니다.
5. “지금 보이는 장면 설명해줘”로 촬영과 음성 응답을 확인합니다.
6. “이거 기억해줘”로 저장하고, 저장한 내용을 다시 질문해 서버 검색을 확인합니다.

### 대화 예시

| 목적 | 말하기 예시 |
| --- | --- |
| 장면 설명 | “지금 보이는 것 설명해줘.” |
| 문서 이해 | “이 문서 읽어줘.” |
| 기억 저장 | “이거 저장해줘. 다음에 살 후보야.” |
| 과거 검색 | “지난주에 본 명함 찾아줘.” |
| 사람 정보 | “박부장 관련 기록 찾아줘.” |
| 회의 검색 | “지난번 미팅에서 나온 니즈 알려줘.” |
| 이전 작업 조회 | “아까 찍은 사진 결과 알려줘.” |
| 작업 제어 | “그 작업 취소해줘.” |

자연어는 고정 명령어 문법이 아닙니다. 여러 작업이 있을 때 대상이 불분명하면 작업 내용을 덧붙여 말하세요. 전화·문자처럼 실제 동작이 발생하는 기능은 연락처 및 확인 과정을 거칩니다.

### 이미지 전송과 회의 녹음

기본적으로 영상 프레임을 계속 AI로 보내지 않고, 시야가 필요한 요청에서 이미지를 가져옵니다. Settings의 **Video Streaming**을 켜면 주기적으로 프레임이 전송되어 데이터 사용량과 AI 비용이 증가할 수 있습니다.

회의 녹음은 실시간 대화와 마이크를 공유합니다. 현재 구현은 회의 녹음을 시작할 때 Live 대화를 종료하며, 두 모드를 동시에 유지하지 않습니다.

## API와 데이터 구조

### 주요 API

기본 메모리·사람·회의·에이전트 API는 `X-API-Key` 헤더로 인증합니다. 상태 확인, 일부 스트리밍·OAuth 경로는 인증 방식이 다르므로 모든 경로가 이 헤더 하나로 보호된다고 가정하지 마세요. 전체 요청·응답 스키마는 실행 중인 서버의 `/docs`에서 확인합니다.

| 영역 | 대표 경로 | 역할 |
| --- | --- | --- |
| 상태 | `GET /health` | API 상태 및 임베딩 설정 확인 |
| 일상 기억 | `POST /memory/life/save` | 메모·AI 해석·사진 저장 |
| 기억 검색 | `POST /memory/search`, `POST /memory/universal-search` | 의미 검색 및 통합 검색 |
| 최근 기억 | `POST /memory/recent` | 최근 저장 기록 조회 |
| 사람 | `POST /people`, `POST /people/search` | 사람 등록·검색 |
| 얼굴 | `POST /people/identify`, `POST /people/{id}/faces` | 얼굴 식별·추가 등록 |
| 관계 맥락 | `GET /people/{id}/context` | 사람·회의·니즈 묶음 조회 |
| 회의 | `POST /meetings`, `POST /meetings/search` | 회의 저장·검색 |
| 녹음 | `POST /meetings/recordings` | 녹음 업로드 및 처리 |
| 니즈 | `POST /needs`, `GET /needs/by-person/{id}` | 사람별 요구사항 관리 |
| 에이전트 | `POST /agent/query`, `POST /agent/confirm` | 서버 에이전트 질의·확인 |
| Flash 프록시 | `POST /agent/flash/generate` | 서버를 통한 Gemini 요청 |
| MCP | `GET /mcp/tools`, `POST /mcp` | 도구 목록·호출 인터페이스 |
| 외부 연동 | `/integrations/*` | OAuth 연결 및 상태 |

### 무엇이 어디에 저장되나요?

| 저장 위치 | 데이터 |
| --- | --- |
| PostgreSQL | 사람, 회의, 니즈, 기억, 관계 및 메타데이터 |
| pgvector 컬럼 | 텍스트 검색 및 얼굴 매칭에 사용하는 벡터 |
| 서버 파일 저장소 | 저장 요청에 포함된 이미지 등 파일 |
| Android 앱 전용 저장소 | 작업 상태, 작업 결과, 촬영 결과 캐시 |
| 로컬 설정 파일 | 개발 환경의 키, 서버 주소, DB 연결 정보 |

일상 기억은 기존 `memories` 구조에 텍스트·발생 시각·관련 사람·JSONB 메타데이터를 저장합니다. 이미지 자체와 이미지에 대한 설명을 구분하며, 장면 검색용 텍스트에는 사용자 메모와 AI 해석 등을 반영합니다.

## 개발 및 검증

### Android 테스트

저장소 루트에서 실행합니다.

```bash
cd frontend/samples/CameraAccessAndroid
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

저장소 통합 시 Android APK 빌드와 JVM 테스트 **27개**가 통과했습니다. 이 검증에는 작업 관리, 도구 라우팅, HTTP 취소, 음성 재생 큐 테스트가 포함됩니다. 실제 글라스·마이크·AI 모델을 연결한 종단 간 테스트를 대체하지는 않습니다.

### 백엔드 확인

```bash
cd backend/jarvis-server
source .venv/bin/activate
python -m compileall -q app scripts
docker compose config --quiet
curl --fail http://localhost:8000/health
```

`test_commands.sh`는 실행 중인 API에 요청을 보내는 수동 확인 스크립트입니다. `API_KEY`를 환경변수로 설정해야 하며, 사람·회의 데이터를 실제로 생성합니다. 테스트용 DB에서 실행하세요.

통합 시 Python 구문 검사, 필수 환경변수 검사, Compose 설정 검증을 수행했습니다. DB·외부 AI·OAuth를 모두 연결한 통합 테스트와 보조 Android·iOS 앱 빌드는 별도 검증이 필요합니다.

## 문제 해결

| 증상 | 확인할 내용 |
| --- | --- |
| Gradle 패키지 다운로드 401/403 | `local.properties`의 GitHub 계정·토큰 및 Meta DAT 패키지 접근 권한 |
| API 시작 시 설정 오류 | `.env`가 `backend/jarvis-server/`에 있는지, 필수 값이 비어 있거나 예제 값인지 확인 |
| DB 인증 실패 | `POSTGRES_PASSWORD`와 `DATABASE_URL` 일치 여부, 기존 볼륨의 실제 비밀번호 |
| 폰에서 API 연결 실패 | 서버 주소, 같은 네트워크, Uvicorn 바인딩, 방화벽, 앱에 저장된 설정 |
| API 응답 401 | 앱의 Jarvis 키와 서버 `API_KEY` 일치 여부 |
| 녹음 전사·요약 실패 | 서버의 `OPENAI_API_KEY`, 외부 연결, 업로드·모델 처리 로그 |
| 검색 결과가 없음 | 실제 저장된 데이터 유무, 임베딩 생성 여부, 검색 조건 |
| 이전 촬영 결과를 찾을 수 없음 | 앱 캐시 만료·정리 여부. 필요한 경우 새로 촬영 |
| 폴더 이동 후 CMake 오류 | 이전 절대 경로가 남은 생성 캐시 `.cxx`, `app/build`를 정리한 뒤 다시 빌드 |
| 기존 APK에 설치 실패 | 기존 설치본과 현재 디버그 빌드의 서명키 차이 확인 |

## 데이터와 운영 설정

음성·이미지·회의록에는 개인 정보가 포함될 수 있습니다. 실시간 대화와 이미지 해석은 외부 AI에 데이터를 전송하고, 저장 요청은 직접 운영하는 Jarvis 서버에 기록합니다. 서버 키를 설정하는 기능에 따라 회의 녹음 등도 외부 AI에서 처리할 수 있습니다.

- `.env`, `Secrets.kt`, `Secrets.swift`, `local.properties`, OAuth 비밀값과 서명키를 커밋하지 않습니다.
- API 키를 포함한 APK를 배포하면 클라이언트의 키를 비밀로 유지할 수 없습니다. 공개 서비스 배포에는 사용자 인증과 서버 측 키 관리 구조를 별도로 설계해야 합니다.
- 기본 실행 예시는 개발 환경용 HTTP입니다. 외부 운영 환경에는 TLS, 접근 제어, DB·이미지 백업 및 보관 정책이 필요합니다.
- AI 호출은 사용량에 따라 비용이 발생할 수 있습니다. 로컬 임베딩을 사용하는 것과 서비스 전체가 오프라인·무료로 동작하는 것은 다릅니다.
- 현재 작업 취소는 이미 발생한 촬영이나 완료된 서버 저장을 자동으로 되돌리지 않습니다.

새 저장소는 GitHub 포크가 아닌 독립 저장소이지만 클라이언트의 기존 Git 이력을 유지합니다. 최신 파일에서 비밀값을 제외했더라도 과거 이력까지 자동 삭제되는 것은 아닙니다. 기존 이력의 샘플 서명키는 운영 서명용으로 사용하지 않습니다.

## 관련 문서와 라이선스

- [실시간 대화 구현](frontend/samples/CameraAccessAndroid/docs/live-conversation.md)
- [작업 관리 구현 및 검증](frontend/samples/CameraAccessAndroid/docs/2026-09-19-task-management-changes.md)
- [작업 오케스트레이션 설계](frontend/samples/CameraAccessAndroid/docs/task-orchestration.md)
- [모노레포 통합 기록](docs/monorepo-migration.md)
- [Android 클라이언트 상세 문서](frontend/samples/CameraAccessAndroid/README.md)
- [백엔드 상세 문서](backend/jarvis-server/README.md)

하위 문서에는 통합 전 경로와 과거 설계 설명이 남아 있을 수 있습니다. 현재 폴더 구조와 시작 절차는 이 README를 기준으로 확인하세요. 코드 및 도구 모델의 변경은 각 하위 프로젝트에서 함께 관리합니다.

원본 코드의 저작권 고지와 [LICENSE](LICENSE), [NOTICE](NOTICE)를 유지합니다. 루트 LICENSE에는 Meta Wearables 개발자 약관이 명시되어 있으며, SDK·모델·외부 라이브러리에는 각각의 약관과 라이선스가 적용됩니다. 이 저장소 전체에 MIT 등의 단일 라이선스가 적용된다고 가정하지 마세요.
