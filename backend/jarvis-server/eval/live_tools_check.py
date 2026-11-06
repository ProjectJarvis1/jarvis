"""Gemini Live 한 세션에서 내장 구글 검색과 앱 도구 호출이 함께 되는지 확인한다.

  python -I eval/live_tools_check.py --key-file <Secrets.kt>

각 질문마다 검색이 쓰였는지(groundingMetadata), 어떤 앱 도구를 불렀는지, 첫 답까지 걸린 시간,
답 내용을 출력한다. 여러 질문을 한 세션에서 이어서 보내 여러 턴도 함께 본다.
"""
import argparse
import asyncio
import json
import re
import time
from pathlib import Path

import websockets

MODEL = "models/gemini-3.1-flash-live-preview"
URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={key}"

TOOLS = [
    {"googleSearch": {}},
    {"functionDeclarations": [
        {"name": "notion_search", "description": "사용자의 노션 페이지를 검색합니다. 사용자가 노션을 말할 때만 씁니다.",
         "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}},
        {"name": "save_memory", "description": "사용자가 기억하거나 메모해 달라고 한 내용을 저장합니다.",
         "parameters": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
    ]},
]

QUESTIONS = [
    ("검색만", "오늘 서울 날씨 어때?"),
    ("앱 도구만", "내 노션에서 견적서 찾아줘"),
    ("검색 후 도구", "엔비디아 최근 주가 찾아보고 그 내용 메모로 저장해 줘"),
    ("검색 없이 일반 대화", "고마워"),
]


async def main(key: str) -> None:
    async with websockets.connect(URL.format(key=key), max_size=None) as ws:
        await ws.send(json.dumps({"setup": {
            "model": MODEL,
            "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {"languageCode": "ko-KR"}},
            "systemInstruction": {"parts": [{"text": "한국어로 한두 문장으로 짧게 답하세요. 최신 정보가 필요하면 구글 검색을 쓰세요."}]},
            "tools": TOOLS,
            "outputAudioTranscription": {},
        }}))
        print("setup:", (await ws.recv())[:200])
        for label, text in QUESTIONS:
            t0 = time.monotonic()
            first = None
            said, calls, grounded, queries = "", [], False, []
            await ws.send(json.dumps({"realtimeInput": {"text": text}}))
            try:
                while True:
                    msg = json.loads(await asyncio.wait_for(ws.recv(), 30))
                    if "toolCall" in msg:
                        replies = []
                        for fc in msg["toolCall"].get("functionCalls", []):
                            calls.append(f'{fc["name"]}({json.dumps(fc.get("args", {}), ensure_ascii=False)})')
                            replies.append({"id": fc["id"], "name": fc["name"], "response": {"result": "완료했습니다."}})
                        await ws.send(json.dumps({"toolResponse": {"functionResponses": replies}}))
                    sc = msg.get("serverContent", {})
                    if sc.get("modelTurn") and first is None:
                        first = time.monotonic() - t0
                    gm = sc.get("groundingMetadata")
                    if gm:
                        grounded = True
                        queries += gm.get("webSearchQueries", [])
                    for part in (sc.get("modelTurn") or {}).get("parts", []):
                        if "executableCode" in part or "toolCall" in part:
                            calls.append("내장:" + json.dumps(part, ensure_ascii=False)[:120])
                    if "outputTranscription" in sc:
                        said += sc["outputTranscription"].get("text", "")
                    if sc.get("turnComplete"):
                        break
            except asyncio.TimeoutError:
                said += " [30초 안에 끝나지 않음]"
            print(f"\n[{label}] {text}")
            print(f"  검색 사용: {grounded} {queries}")
            print(f"  앱 도구: {calls or '없음'}")
            print(f"  첫 답까지: {first:.2f}초" if first else "  첫 답 없음")
            print(f"  답: {said.strip()}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--key-file", required=True, type=Path)
    args = ap.parse_args()
    key = re.search(r'geminiAPIKey\s*=\s*"([^"]+)"', args.key_file.read_text()).group(1)
    asyncio.run(main(key))
