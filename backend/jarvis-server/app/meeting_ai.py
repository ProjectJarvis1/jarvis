import json
from pathlib import Path

from openai import OpenAI

from .config import settings
from .followups import normalize_followups


def _client() -> OpenAI:
    if not settings.openai_api_key:
        raise RuntimeError("GPT_API_KEY or OPENAI_API_KEY is not configured")
    return OpenAI(api_key=settings.openai_api_key)


def transcribe_audio(path: Path) -> str:
    with path.open("rb") as audio_file:
        transcript = _client().audio.transcriptions.create(
            model=settings.openai_transcription_model,
            file=audio_file,
            response_format="text",
            language="ko",
        )
    if isinstance(transcript, str):
        return transcript.strip()
    return str(getattr(transcript, "text", transcript)).strip()


def summarize_transcript(transcript: str, title: str = "") -> dict:
    schema = {
        "type": "object",
        "properties": {
            "title": {"type": "string"},
            "summary": {"type": "string"},
            "markdown_summary": {"type": "string"},
            "decisions": {"type": "array", "items": {"type": "string"}},
            "action_items": {"type": "array", "items": {"type": "string"}},
            "followups": {"type": "array", "items": {"type": "object", "properties": {
                "task": {"type": "string"}, "owner": {"type": "string"},
                "due_at": {"type": "string"}, "due_text": {"type": "string"}, "evidence": {"type": "string"}},
                "required": ["task", "owner", "due_at", "due_text", "evidence"], "additionalProperties": False}},
            "people": {"type": "array", "items": {"type": "string"}},
            "companies": {"type": "array", "items": {"type": "string"}},
            "keywords": {"type": "array", "items": {"type": "string"}},
        },
        "required": [
            "title",
            "summary",
            "markdown_summary",
            "decisions",
            "action_items",
            "followups",
            "people",
            "companies",
            "keywords",
        ],
        "additionalProperties": False,
    }
    response = _client().chat.completions.create(
        model=settings.openai_summary_model,
        messages=[
            {
                "role": "system",
                "content": (
                    "한국어 회의록 정리 도우미입니다. transcript에 없는 사실은 만들지 마세요. "
                    "핵심 요약, 결정사항, 실행할 일, 사람, 회사를 구조화하세요. "
                    "followups에는 할 일(task), 담당자(owner), 명시된 기한(due_at), 원문 인용(evidence)을 넣으세요. "
                    "담당자와 기한이 없으면 빈 문자열로 두고 날짜를 추측하지 마세요. evidence는 원문 그대로 인용하세요. "
                    "markdown_summary는 노션 회의록처럼 읽기 좋은 마크다운으로 작성하세요. "
                    "형식은 다음을 따르세요: "
                    "# 제목, "
                    "## 핵심 요약, "
                    "## 결정사항, "
                    "## 후속 작업, "
                    "## 언급된 사람/회사, "
                    "## 원문 핵심 포인트. "
                    "강조가 필요한 부분은 **굵게** 표시하고, 항목은 불릿 리스트를 사용하세요."
                ),
            },
            {
                "role": "user",
                "content": f"회의 제목 힌트: {title or '없음'}\n\n회의 transcript:\n{transcript}",
            },
        ],
        response_format={
            "type": "json_schema",
            "json_schema": {
                "name": "meeting_summary",
                "strict": True,
                "schema": schema,
            },
        },
        temperature=0.1,
    )
    content = response.choices[0].message.content or "{}"
    result = json.loads(content)
    # Reject unsupported evidence; retain the original transcript as the authority.
    result["followups"] = normalize_followups(result.get("followups", []), transcript)
    return result
