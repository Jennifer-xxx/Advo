"""Aggregate diary entries in code, let Gemma write only the narrative, render a one-page HTML report."""
from __future__ import annotations

import html
import json
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path

from common import LANG_NAME, SHARED, fill, load_knowledge, load_prompt, norm
from interview import match_condition

LABELS = {
    "zh": {
        "title": "症状记录报告", "period": "记录时间", "entries": "条记录", "narrative": "病情陈述",
        "narrative_note": "由手机上的 AI 根据记录整理，患者已审阅。", "overview": "症状概览",
        "symptom": "症状", "count": "次数", "days": "天数", "severity": "严重度（平均 / 最高）",
        "worse": "加重因素", "better": "缓解方式", "assoc": "伴随症状", "words": "患者用词",
        "trend": "严重度变化", "meds": "用药记录", "med": "药物 / 方法", "times": "次数",
        "effect": "效果", "quotes": "患者原话", "flags": "危险信号提示", "questions": "想问医生的问题",
        "footer": "本报告由患者在手机上记录，设备端 AI 整理，数据未离开手机。不构成诊断或治疗建议。",
        "none": "无", "other": "其他",
        "trend_words": {"rising": "加重", "falling": "减轻", "stable": "大致平稳", "n/a": "数据不足"},
    },
    "en": {
        "title": "Symptom Diary Report", "period": "Period", "entries": "entries", "narrative": "History of present illness",
        "narrative_note": "Drafted by on-device AI from the diary and reviewed by the patient.", "overview": "Overview",
        "symptom": "Symptom", "count": "Times", "days": "Days", "severity": "Severity (avg / max)",
        "worse": "Worse with", "better": "Better with", "assoc": "Associated", "words": "Patient's words",
        "trend": "Severity over time", "meds": "Medicines", "med": "Medicine / measure", "times": "Times",
        "effect": "Effect", "quotes": "In the patient's words", "flags": "Red-flag alerts", "questions": "Questions for the doctor",
        "footer": "Recorded by the patient on their phone and summarized by on-device AI; no data left the device. Not a diagnosis or treatment advice.",
        "none": "None", "other": "Other",
        "trend_words": {"rising": "worsening", "falling": "improving", "stable": "roughly stable", "n/a": "not enough data"},
    },
}


def _entry_date(entry: dict) -> date:
    return datetime.fromisoformat(entry["created_at"]).date()


def _top(counter: Counter, display: dict, n: int = 3) -> list[str]:
    return [display[k] for k, _ in counter.most_common(n)]


def _trend(series: list[int]) -> str:
    if len(series) < 3:
        return "n/a"
    half = len(series) // 2
    first, second = sum(series[:half]) / half, sum(series[half:]) / (len(series) - half)
    if second - first >= 1:
        return "rising"
    if first - second >= 1:
        return "falling"
    return "stable"


def aggregate(entries: list[dict], lang: str = "zh", days: int | None = 14,
              end: date | None = None, knowledge: dict | None = None) -> dict:
    k = knowledge or load_knowledge()
    if not entries:
        return {"period": None, "entry_count": 0, "symptoms": [], "medications": [], "quotes": [], "red_flags": []}
    end = end or max(_entry_date(e) for e in entries)
    start = end - timedelta(days=days - 1) if days else min(_entry_date(e) for e in entries)
    chosen = sorted((e for e in entries if start <= _entry_date(e) <= end), key=lambda e: e["created_at"])

    groups: dict[str, dict] = {}
    meds: dict[str, dict] = {}
    for e in chosen:
        rec, d = e["record"], _entry_date(e)
        cond = match_condition(rec, e.get("raw_text", ""), k)
        name = cond["label"][lang] if cond else (rec.get("symptom") or LABELS[lang]["other"])
        g = groups.setdefault(name, {"name": name, "entries": 0, "dates": set(), "sev_by_date": {},
                                     "sev": [], "c": {f: Counter() for f in ("aggravating", "relieving", "associated", "character")},
                                     "display": {}})
        g["entries"] += 1
        g["dates"].add(d)
        sev = rec.get("severity_0_10")
        if sev is not None:
            g["sev"].append(sev)
            g["sev_by_date"][d] = max(sev, g["sev_by_date"].get(d, 0))
        for f in g["c"]:
            for item in rec.get(f) or []:
                g["c"][f][norm(item)] += 1
                g["display"].setdefault(norm(item), item)
        for m in rec.get("medication") or []:
            mk = norm(m["name"])
            md = meds.setdefault(mk, {"name": m["name"], "times": 0, "effects": []})
            md["times"] += 1
            if m.get("effect"):
                md["effects"].append(f"{d.strftime('%m-%d')}: {m['effect']}")

    symptoms = []
    for g in sorted(groups.values(), key=lambda g: -g["entries"]):
        series = [g["sev_by_date"][d] for d in sorted(g["sev_by_date"])]
        symptoms.append({
            "name": g["name"], "entries": g["entries"], "days_with_symptom": len(g["dates"]),
            "severity_avg": round(sum(g["sev"]) / len(g["sev"]), 1) if g["sev"] else None,
            "severity_min": min(g["sev"]) if g["sev"] else None,
            "severity_max": max(g["sev"]) if g["sev"] else None,
            "severity_trend": _trend(series),
            "severity_by_date": [{"date": d.isoformat(), "severity": s} for d, s in sorted(g["sev_by_date"].items())],
            "top_aggravating": _top(g["c"]["aggravating"], g["display"]),
            "top_relieving": _top(g["c"]["relieving"], g["display"]),
            "top_associated": _top(g["c"]["associated"], g["display"]),
            "patient_terms": _top(g["c"]["character"], g["display"], 4),
        })

    ranked = sorted(chosen, key=lambda e: -(e["record"]["severity_0_10"] if e["record"].get("severity_0_10") is not None else -1))
    quotes = [{"date": _entry_date(e).isoformat(), "text": e["record"].get("patient_quote") or e.get("raw_text", "")[:80]}
              for e in ranked[:3]]
    flags = [{"date": _entry_date(e).isoformat(), "id": fid} for e in chosen for fid in e.get("red_flags") or []]
    return {
        "period": {"start": start.isoformat(), "end": end.isoformat(), "days": (end - start).days + 1},
        "entry_count": len(chosen),
        "symptoms": symptoms,
        "medications": sorted(meds.values(), key=lambda m: -m["times"]),
        "quotes": quotes,
        "red_flags": flags,
    }


def narrative_input(summary: dict) -> dict:
    """The compact view the model sees: no per-day series, no quotes."""
    return {
        "period": summary["period"],
        "entry_count": summary["entry_count"],
        "symptoms": [{k: v for k, v in s.items() if k != "severity_by_date"} for s in summary["symptoms"]],
        "medications": summary["medications"],
    }


def write_narrative(client, summary: dict, lang: str) -> tuple[str, float]:
    prompt = fill(load_prompt("report_narrative"), lang_name=LANG_NAME[lang],
                  summary_json=json.dumps(narrative_input(summary), ensure_ascii=False))
    out = client.complete(prompt, max_tokens=400)
    return out.text.strip(), out.seconds


def severity_svg(symptom: dict, period: dict, width: int = 640, height: int = 180) -> str:
    start = date.fromisoformat(period["start"])
    n = period["days"]
    left, right, top, bottom = 36, 12, 12, 28
    pw, ph = width - left - right, height - top - bottom
    x = lambda i: left + (pw * i / max(n - 1, 1))
    y = lambda s: top + ph * (1 - s / 10)
    pts = [(x((date.fromisoformat(p["date"]) - start).days), y(p["severity"]), p["severity"]) for p in symptom["severity_by_date"]]
    grid = "".join(f'<line x1="{left}" x2="{width - right}" y1="{y(v):.1f}" y2="{y(v):.1f}" class="g"/>'
                   f'<text x="{left - 6}" y="{y(v) + 4:.1f}" text-anchor="end">{v}</text>' for v in (0, 5, 10))
    ticks = "".join(f'<text x="{x(i):.1f}" y="{height - 8}" text-anchor="middle">{(start + timedelta(days=i)).strftime("%m-%d")}</text>'
                    for i in range(0, n, max(1, n // 7)))
    line = " ".join(f"{px:.1f},{py:.1f}" for px, py, _ in pts)
    dots = "".join(f'<circle cx="{px:.1f}" cy="{py:.1f}" r="4"/><text x="{px:.1f}" y="{py - 8:.1f}" text-anchor="middle">{s}</text>'
                   for px, py, s in pts)
    return (f'<svg viewBox="0 0 {width} {height}" class="chart">{grid}{ticks}'
            f'<polyline points="{line}" class="l"/>{dots}</svg>')


def render_html(summary: dict, narrative: str, lang: str = "zh", questions: list[str] | None = None,
                knowledge: dict | None = None) -> str:
    L, esc = LABELS[lang], html.escape
    k = knowledge or load_knowledge()
    flag_msgs = {f["id"]: f["message"][lang] for f in k["red_flags"]}
    joined = lambda xs: esc("、".join(xs) if lang == "zh" else ", ".join(xs)) if xs else L["none"]
    period = summary["period"] or {"start": "-", "end": "-", "days": 0}
    lp, rp = ("（", "）") if lang == "zh" else (" (", ")")

    rows = "".join(
        f"<tr><td><b>{esc(s['name'])}</b></td><td>{s['entries']}</td><td>{s['days_with_symptom']}</td>"
        f"<td>{s['severity_avg'] if s['severity_avg'] is not None else '-'} / {s['severity_max'] if s['severity_max'] is not None else '-'}"
        f"{lp}{L['trend_words'][s['severity_trend']]}{rp}</td>"
        f"<td>{joined(s['top_aggravating'])}</td><td>{joined(s['top_relieving'])}</td>"
        f"<td>{joined(s['top_associated'])}</td><td>{joined(s['patient_terms'])}</td></tr>"
        for s in summary["symptoms"])
    main = next((s for s in summary["symptoms"] if s["severity_by_date"]), None)
    chart = (f"<h2>{L['trend']} · {esc(main['name'])}</h2>{severity_svg(main, period)}" if main else "")
    med_rows = "".join(f"<tr><td>{esc(m['name'])}</td><td>{m['times']}</td><td>{joined(m['effects'])}</td></tr>"
                       for m in summary["medications"])
    meds = (f"<h2>{L['meds']}</h2><table><tr><th>{L['med']}</th><th>{L['times']}</th><th>{L['effect']}</th></tr>{med_rows}</table>"
            if med_rows else "")
    quotes = "".join(f"<li><span class='d'>{q['date'][5:]}</span> “{esc(q['text'])}”</li>" for q in summary["quotes"])
    flags = "".join(f"<li><span class='d'>{f['date'][5:]}</span> {esc(flag_msgs.get(f['id'], f['id']))}</li>" for f in summary["red_flags"])
    qs = "".join(f"<li>{esc(q)}</li>" for q in (questions or []))

    return f"""<!doctype html><html lang="{lang}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{L['title']}</title>
<style>
 body{{font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",Roboto,sans-serif;color:#1f2328;margin:24px;max-width:760px;line-height:1.5}}
 h1{{font-size:22px;margin:0}} h2{{font-size:15px;margin:22px 0 8px;border-bottom:1px solid #d0d7de;padding-bottom:4px}}
 .meta{{color:#57606a;font-size:13px}} table{{border-collapse:collapse;width:100%;font-size:12.5px}}
 th,td{{border:1px solid #d0d7de;padding:5px 7px;text-align:left;vertical-align:top}} th{{background:#f6f8fa}}
 .note{{color:#57606a;font-size:12px}} .d{{color:#57606a;font-variant-numeric:tabular-nums;margin-right:6px}}
 .chart{{width:100%;height:auto}} .chart text{{font-size:10px;fill:#57606a}} .chart .g{{stroke:#eaeef2}}
 .chart .l{{fill:none;stroke:#0969da;stroke-width:2}} .chart circle{{fill:#0969da}}
 .flags li{{color:#cf222e}} footer{{margin-top:24px;color:#57606a;font-size:11.5px;border-top:1px solid #d0d7de;padding-top:8px}}
</style></head><body>
<h1>{L['title']}</h1>
<div class="meta">{L['period']}: {period['start']} – {period['end']} · {summary['entry_count']} {L['entries']}</div>
<h2>{L['narrative']}</h2><p>{esc(narrative)}</p><div class="note">{L['narrative_note']}</div>
<h2>{L['overview']}</h2>
<table><tr><th>{L['symptom']}</th><th>{L['count']}</th><th>{L['days']}</th><th>{L['severity']}</th>
<th>{L['worse']}</th><th>{L['better']}</th><th>{L['assoc']}</th><th>{L['words']}</th></tr>{rows}</table>
{chart}{meds}
<h2>{L['quotes']}</h2><ul>{quotes}</ul>
{f"<h2>{L['flags']}</h2><ul class='flags'>{flags}</ul>" if flags else ""}
{f"<h2>{L['questions']}</h2><ul>{qs}</ul>" if qs else ""}
<footer>{L['footer']}</footer></body></html>"""


def main():
    import argparse

    from gemma_client import DEFAULT_BASE_URL, GemmaClient

    ap = argparse.ArgumentParser(description="Build the doctor report from diary entries.")
    ap.add_argument("--entries", default=str(SHARED / "demo" / "demo_entries.json"))
    ap.add_argument("--lang", choices=["zh", "en"], default="zh")
    ap.add_argument("--days", type=int, default=14)
    ap.add_argument("--out", default="report.html")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--no-model", action="store_true", help="skip Gemma and use a placeholder narrative")
    args = ap.parse_args()

    entries = json.loads(Path(args.entries).read_text(encoding="utf-8"))
    summary = aggregate(entries, lang=args.lang, days=args.days)
    if args.no_model:
        text, secs = "(narrative placeholder: run without --no-model to let Gemma write this)", 0.0
    else:
        text, secs = write_narrative(GemmaClient(args.base_url), summary, args.lang)
    Path(args.out).write_text(render_html(summary, text, args.lang), encoding="utf-8")
    print(json.dumps(narrative_input(summary), ensure_ascii=False, indent=2))
    print(f"\nnarrative: {secs:.1f}s -> wrote {args.out}")


if __name__ == "__main__":
    main()
