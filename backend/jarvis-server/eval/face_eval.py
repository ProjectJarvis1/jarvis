"""Open-set face identification check for the server's real decision rule.

Uses the server's own embedding path (app.face_embeddings.embed_face_from_base64: SCRFD +
ArcFace buffalo_l, same quality gates) and the decision constants parsed from
app/routers/people.py, so a threshold change there is measured without editing this file.

Data: LFW (public research set of news photos; http://vis-www.cs.umass.edu/lfw/).
  python -I eval/face_eval.py --lfw /path/to/lfw --out /path/to/report.json

What it reports, per gallery size (people enrolled) and photos per enrolled person:
  enrolled person probes  -> confident correct / confident WRONG person / uncertain / no_match
  stranger probes         -> confident (false accept) / uncertain / no_match
"confident" is what the assistant asserts without asking; that is the number that matters.
"""
import argparse
import base64
import json
import random
import re
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from app.face_embeddings import embed_face_from_base64  # noqa: E402


def decision_constants():
    src = (ROOT / "app/routers/people.py").read_text(encoding="utf-8")
    pick = lambda name: float(re.search(rf"^{name}\s*=\s*([0-9.]+)", src, re.M).group(1))
    return pick("_CONFIDENT_SCORE"), pick("_MIN_SCORE"), pick("_MIN_MARGIN")


CONFIDENT, MIN_SCORE, MIN_MARGIN = decision_constants()


def decide(scores):
    """Mirror of people._decide_match on a per-person best-score list (desc)."""
    if not scores or scores[0][1] < MIN_SCORE:
        return "no_match", None
    top_id, top = scores[0]
    runner = scores[1][1] if len(scores) > 1 else -1.0
    if top >= CONFIDENT or top - runner >= MIN_MARGIN:
        return "confident", top_id
    return "uncertain", top_id


def embed_all(lfw: Path, people, per_person, cache: Path):
    if cache.exists():
        data = json.loads(cache.read_text())
        return {k: [np.array(e) if e else None for e in v] for k, v in data.items()}
    out, t0, n = {}, time.time(), 0
    for name, files in people.items():
        embs = []
        for f in files[:per_person]:
            emb, count, quality = embed_face_from_base64(base64.b64encode(f.read_bytes()).decode())
            ok = emb is not None and count == 1 and quality is not None
            embs.append(np.array(emb) if ok else None)
            n += 1
            if n % 200 == 0:
                print(f"embedded {n} photos, {time.time() - t0:.0f}s", flush=True)
        out[name] = embs
    cache.write_text(json.dumps({k: [e.tolist() if e is not None else None for e in v] for k, v in out.items()}))
    return out


def run(embs, multi, single, gallery_size, k, trials, rng):
    tally = {"genuine": {"confident_correct": 0, "confident_wrong": 0, "uncertain": 0, "no_match": 0},
             "stranger": {"confident": 0, "uncertain": 0, "no_match": 0}}
    scores_genuine, scores_stranger = [], []
    for _ in range(trials):
        enrolled = rng.sample(multi, gallery_size)
        gallery = {}
        for p in enrolled:
            refs = [e for e in embs[p] if e is not None][:k]
            if len(refs) == k:
                gallery[p] = np.stack(refs)
        if not gallery:
            continue
        others = [p for p in multi if p not in gallery] + single

        def ranked(probe):
            s = sorted(((p, float((g @ probe).max())) for p, g in gallery.items()), key=lambda x: -x[1])
            return s

        for p in gallery:
            for probe in [e for e in embs[p] if e is not None][k:]:
                r = ranked(probe)
                scores_genuine.append(dict(r)[p])
                d, who = decide(r)
                key = "confident_correct" if d == "confident" and who == p else "confident_wrong" if d == "confident" else d
                tally["genuine"][key] += 1
        for p in rng.sample(others, min(len(others), 150)):
            probe = next((e for e in embs[p] if e is not None), None)
            if probe is None:
                continue
            r = ranked(probe)
            scores_stranger.append(r[0][1])
            tally["stranger"][decide(r)[0]] += 1
    return tally, scores_genuine, scores_stranger


def pct(part, whole):
    return round(100.0 * part / whole, 2) if whole else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lfw", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--trials", type=int, default=20)
    args = ap.parse_args()
    rng = random.Random(7)
    dirs = sorted(d for d in args.lfw.iterdir() if d.is_dir())
    files = {d.name: sorted(d.glob("*.jpg")) for d in dirs}
    multi_all = [n for n, f in files.items() if len(f) >= 5]
    single_all = [n for n, f in files.items() if len(f) <= 2]
    multi = rng.sample(multi_all, min(200, len(multi_all)))
    single = rng.sample(single_all, 400)
    people = {n: files[n] for n in multi + single}
    embs = embed_all(args.lfw, people, per_person=6, cache=args.out.with_suffix(".embeddings.json"))
    total = sum(len(v) for v in embs.values())
    unusable = sum(e is None for v in embs.values() for e in v)
    report = {"rule": {"confident": CONFIDENT, "min_score": MIN_SCORE, "min_margin": MIN_MARGIN},
              "photos": total, "photos_rejected_by_quality_or_multi_face": unusable, "cases": []}
    for gallery_size in (1, 5, 20, 100):
        for k in (1, 2):
            tally, sg, ss = run(embs, multi, single, gallery_size, k, args.trials, rng)
            g, s = tally["genuine"], tally["stranger"]
            gn, sn = sum(g.values()), sum(s.values())
            case = {
                "gallery_size": gallery_size, "photos_per_person": k,
                "enrolled_probes": gn, "stranger_probes": sn,
                "enrolled_confident_correct_pct": pct(g["confident_correct"], gn),
                "enrolled_confident_WRONG_person_pct": pct(g["confident_wrong"], gn),
                "enrolled_asked_to_confirm_pct": pct(g["uncertain"], gn),
                "enrolled_not_recognized_pct": pct(g["no_match"], gn),
                "stranger_FALSE_ACCEPT_pct": pct(s["confident"], sn),
                "stranger_asked_to_confirm_pct": pct(s["uncertain"], sn),
                "stranger_rejected_pct": pct(s["no_match"], sn),
                "genuine_score_p10": round(float(np.percentile(sg, 10)), 3) if sg else None,
                "stranger_score_p99": round(float(np.percentile(ss, 99)), 3) if ss else None,
            }
            report["cases"].append(case)
            print(json.dumps(case, ensure_ascii=False), flush=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
