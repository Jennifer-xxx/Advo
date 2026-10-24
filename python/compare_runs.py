"""Compare eval runs side by side, e.g. prompt versions or models.

    python3 compare_runs.py e2b v3 e4b medgemma          # all items
    python3 compare_runs.py e2b v3 --lang en             # English items only

Each name is the --tag you gave eval_extraction.py. Runs are re-scored with the current scorer and
the current post-processing (generic pain words removed from character), so old and new runs compare fairly.
"""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path

from common import SHARED
from eval_extraction import score_item, summarize
from extraction import drop_generic_character

RESULTS = Path(__file__).parent / "results"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tags", nargs="+")
    ap.add_argument("--lang", choices=["zh", "en"])
    args = ap.parse_args()
    test = {json.loads(l)["id"]: json.loads(l)
            for l in (SHARED / "eval" / "extraction_testset.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()}

    tables, extra = {}, {}
    for tag in args.tags:
        items = [json.loads(l) for l in (RESULTS / f"{tag}_items.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
        items = [r for r in items if not args.lang or r["lang"] == args.lang]
        rows = summarize([{"scores": score_item(drop_generic_character(r["pred"]), test[r["id"]]["expected"])} for r in items])
        tables[tag] = {r["field"]: r for r in rows}
        extra[tag] = (sum(r["valid"] for r in items), len(items), statistics.median(r["seconds"] for r in items),
                      sum(r["halluc"] for r in rows))

    w = max(10, *(len(t) + 2 for t in args.tags))
    print(f"{'':<15}" + "".join(f"{t:>{w}}" for t in args.tags))
    print(f"{'JSON valid':<15}" + "".join(f"{f'{v}/{n}':>{w}}" for v, n, _, _ in extra.values()))
    print(f"{'median s':<15}" + "".join(f"{s:>{w}.2f}" for _, _, s, _ in extra.values()))
    print(f"{'halluc total':<15}" + "".join(f"{h:>{w}}" for *_, h in extra.values()))
    print()
    for field in tables[args.tags[0]]:
        cells = []
        for t in args.tags:
            r = tables[t].get(field)
            cells.append(f"{r['score']:.2f}" + (f" h{r['halluc']}" if r and r["halluc"] else "") if r else "-")
        print(f"{field:<15}" + "".join(f"{c:>{w}}" for c in cells))
    print("\nscore = accuracy (single fields) or F1 (list fields); hN = N hallucinated values")


if __name__ == "__main__":
    main()
