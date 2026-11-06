"""Korean speech recognition check against the same Gemini Live model the app uses.

Synthesises each sentence with several macOS Korean voices (`say`), optionally degrades it
(background noise, phone-like band limit), streams it in real time to Gemini Live with the
app's turn-detection settings, and compares the server's input transcription to the text.

  python -I eval/speech_eval.py --key-file <Secrets.kt> --out report.json [--limit N]

Metrics: character error rate (spaces ignored), exact-sentence rate, and whether every
name/number token in the sentence came back intact. Synthetic voices are cleaner than real
speech: treat this as a regression floor, not as the real-world number.
"""
import argparse
import asyncio
import base64
import csv
import json
import re
import subprocess
import tempfile
import time
import wave
from pathlib import Path

import numpy as np
import websockets

MODEL = "models/gemini-3.1-flash-live-preview"
import os
LANG = os.environ.get("SPEECH_LANG", "")  # e.g. ko-KR; empty = model default (auto-detect)
URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={key}"
VOICES = ["Yuna", "Reed (한국어(한국))", "Sandy (한국어(한국))", "Eddy (한국어(한국))"]
CONDITIONS = ["clean", "noise10db", "phone_band"]
KEY_TOKENS = re.compile(r"[가-힣]{2,4}(?= ?(?:님|팀장|대리|상무|이사|책임))|[일이삼사오육칠팔구십백천만공]{2,}|이유나|이윤아|크런치랩스|동림|한빛소프트|워트인텔리전스")


def synth(text: str, voice: str) -> np.ndarray:
    """Raises if the voice is missing: `say` silently writes a near-empty file for an unknown name."""
    with tempfile.TemporaryDirectory() as d:
        aiff, wav = Path(d) / "a.aiff", Path(d) / "a.wav"
        subprocess.run(["say", "-v", voice, "-o", str(aiff), text], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff), str(wav)], check=True)
        with wave.open(str(wav)) as w:
            pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    if pcm.size < 16000 * 0.5:
        raise RuntimeError(f"voice {voice!r} produced no speech")
    return pcm


def degrade(x: np.ndarray, cond: str, rng: np.random.Generator) -> np.ndarray:
    if cond == "noise10db":
        noise = np.cumsum(rng.normal(size=x.size)).astype(np.float32)  # brown-ish, like room hum
        noise -= np.convolve(noise, np.ones(400) / 400, "same")
        noise *= np.sqrt(np.mean(x ** 2) / (np.mean(noise ** 2) * 10 ** (10 / 10)))
        x = x + noise
    elif cond == "phone_band":
        f = np.fft.rfft(x)
        hz = np.fft.rfftfreq(x.size, 1 / 16000)
        f[(hz < 300) | (hz > 3400)] = 0
        x = np.fft.irfft(f, x.size) * 0.5
    return np.clip(x, -32768, 32767)


def cer(ref: str, hyp: str) -> float:
    r, h = ref.replace(" ", ""), re.sub(r"[\s.,?!]", "", hyp)
    d = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(h, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc))
    return d[len(h)] / max(1, len(r))


async def transcribe(key: str, pcm: np.ndarray) -> tuple:
    async with websockets.connect(URL.format(key=key), max_size=None) as ws:
        await ws.send(json.dumps({"setup": {
            "model": MODEL,
            "generationConfig": {"responseModalities": ["AUDIO"], **({"speechConfig": {"languageCode": LANG}} if LANG else {})},
            "systemInstruction": {"parts": [{"text": "사용자는 항상 한국어로 말합니다. 짧게 '네'라고만 답하세요."}]},
            "realtimeInputConfig": {"automaticActivityDetection": {
                "disabled": False, "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
                "endOfSpeechSensitivity": "END_SENSITIVITY_LOW", "silenceDurationMs": 800, "prefixPaddingMs": 200},
                "activityHandling": "NO_INTERRUPTION", "turnCoverage": "TURN_INCLUDES_ALL_INPUT"},
            "inputAudioTranscription": {},
        }}))
        await ws.recv()  # setupComplete
        audio = np.concatenate([np.zeros(3200), pcm, np.zeros(16000 * 2)]).astype(np.int16).tobytes()
        t_end_speech = None
        for i in range(0, len(audio), 3200):  # 100 ms chunks, real time
            await ws.send(json.dumps({"realtimeInput": {"audio": {"mimeType": "audio/pcm;rate=16000",
                                                                  "data": base64.b64encode(audio[i:i + 3200]).decode()}}}))
            if i >= 3200 * 2 + pcm.size * 2 and t_end_speech is None:
                t_end_speech = time.monotonic()
            await asyncio.sleep(0.1)
        text, first_reply = "", None
        try:
            while True:
                msg = json.loads(await asyncio.wait_for(ws.recv(), 8))
                sc = msg.get("serverContent", {})
                if "inputTranscription" in sc:
                    text += sc["inputTranscription"].get("text", "")
                if sc.get("modelTurn") and first_reply is None:
                    first_reply = time.monotonic()
                if sc.get("turnComplete"):
                    break
        except asyncio.TimeoutError:
            pass
        latency = round(first_reply - t_end_speech, 2) if first_reply and t_end_speech else None
        return text.strip(), latency


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key-file", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    key = re.search(r'geminiAPIKey\s*=\s*"([^"]+)"', args.key_file.read_text()).group(1)
    rows = list(csv.DictReader(open(Path(__file__).with_name("speech_sentences.tsv"), encoding="utf-8"), delimiter="\t"))
    if args.limit:
        rows = rows[: args.limit]
    rng = np.random.default_rng(7)
    results = []
    for row in rows:
        for voice in VOICES:
            clean = synth(row["text"], voice)
            for cond in CONDITIONS:
                hyp, latency = await transcribe(key, degrade(clean, cond, rng))
                keys = KEY_TOKENS.findall(row["text"])
                keys_ok = all(k.replace(" ", "") in hyp.replace(" ", "") for k in keys)
                r = {"id": row["id"], "kind": row["kind"], "voice": voice, "cond": cond, "ref": row["text"],
                     "hyp": hyp, "cer": round(cer(row["text"], hyp), 3), "keys": keys, "keys_ok": keys_ok,
                     "reply_latency_s": latency}
                results.append(r)
                print(json.dumps(r, ensure_ascii=False), flush=True)
    summary = {}
    for cond in CONDITIONS:
        sub = [r for r in results if r["cond"] == cond]
        lat = [r["reply_latency_s"] for r in sub if r["reply_latency_s"] is not None]
        summary[cond] = {
            "utterances": len(sub),
            "mean_cer_pct": round(100 * float(np.mean([r["cer"] for r in sub])), 1),
            "exact_sentence_pct": round(100 * float(np.mean([r["cer"] == 0 for r in sub])), 1),
            "names_numbers_intact_pct": round(100 * float(np.mean([r["keys_ok"] for r in sub if r["keys"]] or [0])), 1),
            "empty_transcript_pct": round(100 * float(np.mean([not r["hyp"] for r in sub])), 1),
            "reply_latency_median_s": round(float(np.median(lat)), 2) if lat else None,
        }
    args.out.write_text(json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=2))
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
