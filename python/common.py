"""Shared helpers: paths, prompt/knowledge loading, record fields, text normalization."""
from __future__ import annotations

import json
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHARED = ROOT / "shared"

FIELDS = [
    "symptom", "location", "onset", "duration", "character", "severity_0_10",
    "timing", "aggravating", "relieving", "associated", "medication", "patient_quote",
]
STRING_FIELDS = ["symptom", "location", "onset", "duration", "timing", "patient_quote"]
LIST_FIELDS = ["character", "aggravating", "relieving", "associated"]

LANG_NAME = {"zh": "Simplified Chinese", "en": "English"}

_CJK = re.compile(r"[一-鿿]")


def load_prompt(name: str) -> str:
    return (SHARED / "prompts" / f"{name}.txt").read_text(encoding="utf-8")


def fill(template: str, **values) -> str:
    """Replace {{key}} placeholders. Plain replace so JSON braces in prompts stay intact."""
    for key, value in values.items():
        template = template.replace("{{" + key + "}}", str(value))
    return template


def load_knowledge(path: Path | None = None) -> dict:
    path = path or SHARED / "knowledge" / "symptom_knowledge.json"
    return json.loads(Path(path).read_text(encoding="utf-8"))


def detect_lang(text: str) -> str:
    return "zh" if _CJK.search(text or "") else "en"


def empty_record() -> dict:
    record = {f: None for f in FIELDS}
    for f in LIST_FIELDS:
        record[f] = []
    record["medication"] = []
    return record


def is_empty(value) -> bool:
    return value is None or value == "" or value == [] or value == {}


def norm(text: str) -> str:
    """Lowercase, NFKC, drop whitespace and punctuation. Used for fuzzy comparison only."""
    text = unicodedata.normalize("NFKC", str(text)).lower()
    return "".join(
        ch for ch in text
        if not ch.isspace() and not unicodedata.category(ch).startswith(("P", "S"))
    )


def _bigrams(text: str) -> set[str]:
    return {text[i:i + 2] for i in range(len(text) - 1)} or {text}


def _is_subsequence(short: str, long: str) -> bool:
    it = iter(long)
    return all(ch in it for ch in short)


def soft_match(pred: str, alternatives) -> bool:
    """True when `pred` plausibly states one of the expected alternatives."""
    if pred is None:
        return False
    if isinstance(alternatives, str):
        alternatives = [alternatives]
    p = norm(pred)
    if not p:
        return False
    for alt in alternatives:
        a = norm(alt)
        if not a:
            continue
        if a in p:                       # prediction mentions the expected phrase
            return True
        if p in a and len(p) >= 2:       # prediction is a shorter piece of it
            return True
        if len(a) >= 2 and len(p) <= 3 * len(a) and _is_subsequence(a, p):  # 拉肚子 vs 拉了两次肚子
            return True
        ba, bp = _bigrams(a), _bigrams(p)
        if len(ba & bp) / len(ba | bp) >= 0.5:
            return True
    return False
