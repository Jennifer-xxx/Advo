"""Follow-up interview: the model extracts and phrases; code decides what to ask and when to stop."""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field
from datetime import datetime

from common import (LANG_NAME, LIST_FIELDS, detect_lang, empty_record, fill, is_empty,
                    load_knowledge, load_prompt, norm)
from extraction import extract, known_summary, merge_records

_ZH_NEG = ("没有", "没", "不", "无")
_EN_NEG = ("no ", "not ", "never ", "without ", "denies ")


def _affirmed(text: str, keyword: str) -> bool:
    """keyword occurs in text at least once without a negation right before it."""
    text, keyword = text.lower(), keyword.lower()
    start = 0
    while True:
        i = text.find(keyword, start)
        if i < 0:
            return False
        if not (any(n in text[max(0, i - 2):i] for n in _ZH_NEG)
                or any(n in text[max(0, i - 10):i] for n in _EN_NEG)):
            return True
        start = i + 1


def detect_red_flags(text: str, knowledge: dict) -> list[dict]:
    hits = []
    for flag in knowledge["red_flags"]:
        if "any_of" in flag:
            ok = any(_affirmed(text, kw) for kw in flag["any_of"])
        else:
            ok = all(any(_affirmed(text, kw) for kw in group) for group in flag["all_of"])
        if ok:
            hits.append(flag)
    return hits


def match_condition(record: dict, raw_text: str, knowledge: dict):
    """First condition whose keywords appear in the symptom, then location, then raw text."""
    for blob in (record.get("symptom") or "", record.get("location") or "", raw_text or ""):
        low = blob.lower()
        for cond in knowledge["conditions"]:
            if any(kw.lower() in low for kw in cond["match"]):
                return cond
    return None


def record_text(record: dict) -> str:
    parts = []
    for k, v in record.items():
        if isinstance(v, list):
            parts.extend(m["name"] + " " + (m.get("effect") or "") if isinstance(m, dict) else m for m in v)
        elif v is not None:
            parts.append(str(v))
    return " ".join(parts)


@dataclass
class Question:
    slot: str
    text: str
    options: list[str] = field(default_factory=list)
    multi: bool = False


class Interview:
    def __init__(self, client, knowledge: dict | None = None, lang: str | None = None,
                 rephrase: bool = False):
        self.client = client
        self.k = knowledge or load_knowledge()
        self.lang = lang
        self.rephrase = rephrase
        self.record = empty_record()
        self.texts: list[str] = []
        self.asked: list[str] = []
        self.red_flags: list[dict] = []
        self.condition = None
        self.current: Question | None = None
        self.timings: list[tuple[str, float]] = []

    # --- public API -------------------------------------------------------------------
    def start(self, text: str) -> dict:
        self.lang = self.lang or detect_lang(text)
        self._take_text(text, question=None)
        return self._step()

    def answer(self, text: str | None = None, options: list[str] | None = None) -> dict:
        q = self.current
        if options and q:
            self._take_options(q.slot, options)
        if text and text.strip():
            self._take_text(text, question=q.text if q else None)
        return self._step()

    def entry(self) -> dict:
        return {
            "id": uuid.uuid4().hex[:12],
            "created_at": datetime.now().strftime("%Y-%m-%dT%H:%M"),
            "lang": self.lang,
            "raw_text": " / ".join(self.texts),
            "record": self.record,
            "condition": self.condition["id"] if self.condition else None,
            "red_flags": [f["id"] for f in self.red_flags],
            "followups": len(self.asked),
        }

    # --- internals ----------------------------------------------------------------------
    def _take_text(self, text: str, question: str | None):
        self.texts.append(text.strip())
        new, meta = extract(self.client, text, question=question,
                            known=known_summary(self.record) if question else None, lang=self.lang)
        self.timings.append(("extract", meta["seconds"]))
        self.record = merge_records(self.record, new)

    def _take_options(self, slot: str, options: list[str]):
        none_words = {norm(w) for w in self.k["none_answers"]}
        picked = [o for o in options if norm(o) not in none_words]
        self.texts.append("、".join(options))
        if not picked:
            return
        if slot == "severity_0_10":
            try:
                self.record[slot] = int(picked[0])
            except ValueError:
                pass
        elif slot in LIST_FIELDS:
            self.record = merge_records(self.record, {slot: picked})
        elif slot == "medication":
            self.record = merge_records(self.record, {"medication": [{"name": p, "effect": None} for p in picked]})
        else:
            self.record[slot] = self.record[slot] or "、".join(picked)

    def _step(self) -> dict:
        blob = " ".join(self.texts) + " " + record_text(self.record)
        known_ids = {f["id"] for f in self.red_flags}
        new_flags = [f for f in detect_red_flags(blob, self.k) if f["id"] not in known_ids]
        self.red_flags.extend(new_flags)
        self.condition = self.condition or match_condition(self.record, " ".join(self.texts), self.k)
        self.current = self._next_question()
        return {
            "red_flags": [f["message"][self.lang] for f in new_flags],
            "question": self.current,
            "record": self.record,
        }

    def _next_question(self) -> Question | None:
        if len(self.asked) >= self.k["max_followups"]:
            return None
        for slot in self.k["slot_order"]:
            if slot in self.asked or not is_empty(self.record.get(slot)):
                continue
            spec = self.k["slot_questions"][slot]
            text, options, multi = spec[self.lang], spec["options"][self.lang], spec["multi"]
            if slot == "associated" and self.condition:
                text = self.condition["associated_question"][self.lang]
                options = self.condition["associated_checklist"][self.lang]
                multi = True
            if self.rephrase and slot != "severity_0_10":
                text = self._rephrase(text)
            self.asked.append(slot)
            return Question(slot, text, list(options), multi)
        return None

    def _rephrase(self, question: str) -> str:
        prompt = fill(load_prompt("rephrase"), lang_name=LANG_NAME[self.lang],
                      known=known_summary(self.record), question=question)
        try:
            out = self.client.complete(prompt, max_tokens=80)
        except Exception:
            return question
        self.timings.append(("rephrase", out.seconds))
        text = out.text.strip().strip('"').strip()
        return text if 0 < len(text) <= 140 and "{" not in text else question


def main():
    import argparse

    from gemma_client import DEFAULT_BASE_URL, GemmaClient

    ap = argparse.ArgumentParser(description="Chat through one diary entry in the terminal.")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--rephrase", action="store_true", help="let Gemma reword each follow-up (one extra call each)")
    ap.add_argument("--save", help="append the finished entry to this JSON file")
    args = ap.parse_args()

    iv = Interview(GemmaClient(args.base_url), rephrase=args.rephrase)
    state = iv.start(input("描述一下你哪里不舒服 / How are you feeling?\n> "))
    while True:
        for msg in state["red_flags"]:
            print(f"\n[!] {msg}")
        q = state["question"]
        if q is None:
            break
        hint = f"  选项/options: {' | '.join(q.options)}" if q.options else ""
        reply = input(f"\n{q.text}\n{hint}\n> ").strip()
        chosen = [o for o in q.options if o == reply] if q.options else []
        state = iv.answer(options=chosen or None, text=None if chosen else reply)

    entry = iv.entry()
    print("\n" + json.dumps(entry, ensure_ascii=False, indent=2))
    print("timings:", ", ".join(f"{k} {s:.1f}s" for k, s in iv.timings))
    if args.save:
        from pathlib import Path
        path = Path(args.save)
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else []
        data.append(entry)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"saved to {path}")


if __name__ == "__main__":
    main()
