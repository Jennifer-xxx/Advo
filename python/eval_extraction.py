"""Score Gemma's symptom extraction against shared/eval/extraction_testset.jsonl.

Expected-value conventions (per field in "expected"):
  string fields   "text" or ["alt 1", "alt 2"]; absent = must be null; "__any__" = not scored
  severity_0_10   integer; absent = must be null
  list fields     [["alt a", "alt a2"], ["alt b"]]; absent = must be []; "__any__" = not scored
  medication      [{"name": ["alt"]}]; scored on names only
  timing and patient_quote are only scored when present.
"""
from __future__ import annotations

import argparse
import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path

from common import LIST_FIELDS, SHARED, soft_match
from extraction import extract

ANY = "__any__"
SKIP_BY_DEFAULT = {"timing", "patient_quote"}
SCALAR = ["symptom", "location", "onset", "duration", "timing", "severity_0_10"]
LISTS = LIST_FIELDS + ["medication"]


def load_testset(path: Path, lang: str | None = None) -> list[dict]:
    items = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    return [it for it in items if not lang or it["lang"] == lang]


def _match_lists(pred: list[str], expected: list) -> tuple[int, int, int]:
    used, tp = set(), 0
    for alts in expected:
        alts = alts if isinstance(alts, list) else [alts]
        for j, p in enumerate(pred):
            if j not in used and soft_match(p, alts):
                used.add(j)
                tp += 1
                break
    return tp, len(pred) - len(used), len(expected) - tp


def score_item(pred: dict, expected: dict) -> dict:
    """Per-field outcome: scalar -> {"ok", "halluc", "miss"}; list -> {"tp","fp","fn","halluc"}."""
    res = {}
    for f in SCALAR:
        if f in SKIP_BY_DEFAULT and f not in expected:
            continue
        exp = expected.get(f)
        if exp == ANY:
            continue
        p = pred.get(f)
        if f == "severity_0_10":
            res[f] = {"ok": p == exp, "halluc": exp is None and p is not None, "miss": exp is not None and p is None}
        elif exp is None:
            res[f] = {"ok": p is None, "halluc": p is not None, "miss": False}
        else:
            res[f] = {"ok": soft_match(p, exp), "halluc": False, "miss": p is None}
    for f in LISTS:
        exp = expected.get(f, [])
        if exp == ANY:
            continue
        if f == "medication":
            p = [m["name"] for m in pred.get(f) or []]
            exp = [m["name"] for m in exp]
        else:
            p = pred.get(f) or []
        tp, fp, fn = _match_lists(p, exp)
        res[f] = {"tp": tp, "fp": fp, "fn": fn, "halluc": not exp and bool(p)}
    return res


def summarize(results: list[dict]) -> list[dict]:
    agg = defaultdict(lambda: defaultdict(int))
    for r in results:
        for f, o in r["scores"].items():
            a = agg[f]
            a["n"] += 1
            a["halluc"] += int(o["halluc"])
            if "ok" in o:
                a["ok"] += int(o["ok"])
                a["miss"] += int(o["miss"])
            else:
                for key in ("tp", "fp", "fn"):
                    a[key] += o[key]
    rows = []
    for f in SCALAR + LISTS:
        if f not in agg:
            continue
        a = agg[f]
        if f in SCALAR:
            rows.append({"field": f, "n": a["n"], "score": a["ok"] / a["n"], "metric": "accuracy",
                         "halluc": a["halluc"], "miss": a["miss"]})
        else:
            prec = a["tp"] / (a["tp"] + a["fp"]) if a["tp"] + a["fp"] else 1.0
            rec = a["tp"] / (a["tp"] + a["fn"]) if a["tp"] + a["fn"] else 1.0
            f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
            rows.append({"field": f, "n": a["n"], "score": f1, "metric": f"F1 (P {prec:.2f} R {rec:.2f})",
                         "halluc": a["halluc"], "miss": a["fn"]})
    return rows


def oracle_client(items: list[dict]):
    """Returns the first expected alternative as JSON. Checks that perfect output scores 100%."""
    from gemma_client import FunctionClient

    by_text = {it["text"].replace('"', "'"): it["expected"] for it in items}

    def first(v):
        if v == ANY:
            return None
        if isinstance(v, list) and v and isinstance(v[0], list):
            return [x[0] for x in v]
        if isinstance(v, list) and v and isinstance(v[0], dict):
            return [{"name": m["name"][0], "effect": None} for m in v]
        return v[0] if isinstance(v, list) and v else v

    def fn(prompt: str) -> str:
        text = prompt.rsplit('Patient: "', 1)[1].rsplit('"', 1)[0]
        exp = by_text[text]
        return "```json\n" + json.dumps({k: first(v) for k, v in exp.items()}, ensure_ascii=False) + "\n```"

    return FunctionClient(fn)


def main():
    from gemma_client import DEFAULT_BASE_URL, GemmaClient

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--model", default=None, help="model name/id the server expects (litert-lm: see `litert-lm list`)")
    ap.add_argument("--testset", default=str(SHARED / "eval" / "extraction_testset.jsonl"))
    ap.add_argument("--lang", choices=["zh", "en"])
    ap.add_argument("--limit", type=int)
    ap.add_argument("--tag", default="run", help="name for the output files, e.g. e2b or e4b")
    ap.add_argument("--out-dir", default=str(Path(__file__).parent / "results"))
    ap.add_argument("--oracle", action="store_true", help="score perfect answers (tests the scorer, no model)")
    args = ap.parse_args()

    items = load_testset(Path(args.testset), args.lang)[: args.limit]
    client = oracle_client(items) if args.oracle else (
        GemmaClient(args.base_url, args.model) if args.model else GemmaClient(args.base_url))

    results = []
    for i, it in enumerate(items, 1):
        rec, meta = extract(client, it["text"], lang=it["lang"])
        results.append({"id": it["id"], "lang": it["lang"], "valid": meta["valid"], "attempts": meta["attempts"],
                        "seconds": meta["seconds"], "pred": rec, "scores": score_item(rec, it["expected"])})
        print(f"[{i}/{len(items)}] {it['id']} valid={meta['valid']} {meta['seconds']:.1f}s")

    rows = summarize(results)
    secs = [r["seconds"] for r in results]
    print(f"\nJSON valid: {sum(r['valid'] for r in results)}/{len(results)}  "
          f"retries: {sum(r['attempts'] - 1 for r in results)}  "
          f"latency median {statistics.median(secs):.2f}s  max {max(secs):.2f}s")
    print(f"\n{'field':<15}{'n':>4}  {'score':>6}  {'metric':<22}{'halluc':>7}{'miss':>6}")
    for r in rows:
        print(f"{r['field']:<15}{r['n']:>4}  {r['score']:>6.2f}  {r['metric']:<22}{r['halluc']:>7}{r['miss']:>6}")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{args.tag}_items.jsonl").write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in results), encoding="utf-8")
    with open(out / f"{args.tag}_summary.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=["field", "n", "score", "metric", "halluc", "miss"])
        w.writeheader()
        w.writerows(rows)
    print(f"\nwrote {out}/{args.tag}_items.jsonl and {args.tag}_summary.csv")


if __name__ == "__main__":
    main()
