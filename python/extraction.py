"""Turn free-form symptom text into a fixed JSON record, robustly."""
from __future__ import annotations

import json
import re

from common import (FIELDS, LIST_FIELDS, SHARED, STRING_FIELDS, detect_lang, empty_record, fill,
                    is_empty, load_prompt, norm)

_FENCE = re.compile(r"```(?:json)?", re.IGNORECASE)
_TRAILING_COMMA = re.compile(r",\s*([}\]])")
_NULLISH = {"null", "none", "n/a", "na", "unknown", "无", "未知", "未提及", "不详"}
# Words that say "it hurts" without describing HOW it feels; the model likes to put them in character.
GENERIC_CHARACTER = {"疼", "痛", "疼痛", "很疼", "很痛", "特别疼", "特别痛", "有点疼", "有点痛", "难受", "不舒服",
                     "hurt", "hurts", "hurting", "pain", "painful", "sore", "uncomfortable"}


_NUMBER = re.compile(r"\d|[零一二两三四五六七八九十]\s*分|\b(zero|one|two|three|four|five|six|seven|eight|nine|ten)\b",
                     re.IGNORECASE)


def guard_severity(record: dict, text: str) -> dict:
    """Safety rule: keep a 0-10 score only if the patient's words contain a number."""
    if record.get("severity_0_10") is not None and not _NUMBER.search(text or ""):
        return {**record, "severity_0_10": None}
    return record


def drop_generic_character(record: dict) -> dict:
    generic = {norm(g) for g in GENERIC_CHARACTER}
    keep = [c for c in record["character"] if norm(c) not in generic]
    return {**record, "character": keep}


def known_summary(record: dict) -> str:
    """Compact JSON of the non-empty fields, used as context in follow-up prompts."""
    known = {k: v for k, v in record.items() if not is_empty(v)}
    return json.dumps(known, ensure_ascii=False) if known else "{}"


def extract_prompt_name(lang: str) -> str:
    """One prompt per language (extract.zh / extract.en); falls back to the shared extract.txt."""
    return f"extract.{lang}" if (SHARED / "prompts" / f"extract.{lang}.txt").exists() else "extract"


def build_extract_prompt(text: str, question: str | None = None, known: str | None = None,
                         lang: str | None = None) -> str:
    context = ""
    if question:
        context = fill(load_prompt("followup_context"), question=question, known=known or "{}")
    name = extract_prompt_name(lang or detect_lang(text))
    return fill(load_prompt(name), context=context, text=text.replace('"', "'"))


def parse_json_object(raw: str) -> dict:
    """Pull the first balanced {...} out of model output. Raises ValueError if there is none."""
    text = _FENCE.sub("", raw or "")
    start = text.find("{")
    if start < 0:
        raise ValueError("no JSON object in output")
    depth, in_str, esc = 0, False, False
    for i in range(start, len(text)):
        ch = text[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                candidate = text[start:i + 1]
                try:
                    return json.loads(candidate)
                except json.JSONDecodeError:
                    return json.loads(_TRAILING_COMMA.sub(r"\1", candidate))
    raise ValueError("unbalanced JSON object in output")


def _clean_str(value):
    if value is None:
        return None
    if isinstance(value, (list, tuple)):
        value = "，".join(str(v) for v in value if not is_empty(v))
    s = str(value).strip()
    return None if not s or s.lower() in _NULLISH else s


def _clean_list(value) -> list[str]:
    if is_empty(value):
        return []
    if isinstance(value, str):
        value = [value]
    out, seen = [], set()
    for item in value:
        s = _clean_str(item)
        if s and norm(s) not in seen:
            seen.add(norm(s))
            out.append(s)
    return out


def _clean_severity(value):
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        n = value
    else:
        m = re.search(r"\d+(?:\.\d+)?", str(value))
        if not m:
            return None
        n = float(m.group())
    n = int(round(n))
    return n if 0 <= n <= 10 else None


def _clean_meds(value) -> list[dict]:
    if is_empty(value):
        return []
    if isinstance(value, (str, dict)):
        value = [value]
    out, seen = [], set()
    for item in value:
        if isinstance(item, dict):
            name = _clean_str(item.get("name"))
            effect = _clean_str(item.get("effect"))
        else:
            name, effect = _clean_str(item), None
        if name and norm(name) not in seen:
            seen.add(norm(name))
            out.append({"name": name, "effect": effect})
    return out


def normalize_record(obj: dict) -> dict:
    record = empty_record()
    for f in STRING_FIELDS:
        record[f] = _clean_str(obj.get(f))
    for f in LIST_FIELDS:
        record[f] = _clean_list(obj.get(f))
    record["severity_0_10"] = _clean_severity(obj.get("severity_0_10"))
    record["medication"] = _clean_meds(obj.get("medication"))
    return record


def extract(client, text: str, question: str | None = None, known: str | None = None,
            retries: int = 1, max_tokens: int = 400, lang: str | None = None):
    """Returns (record, meta). meta = {valid, attempts, seconds, raw}."""
    prompt = build_extract_prompt(text, question, known, lang)
    meta = {"valid": False, "attempts": 0, "seconds": 0.0, "raw": "", "finish": "", "thinking_chars": 0}
    for attempt in range(retries + 1):
        meta["attempts"] = attempt + 1
        p = prompt if attempt == 0 else prompt + "\n(Your previous output was not valid JSON. Output only the JSON object.)\nJSON:"
        out = client.complete(p, max_tokens=max_tokens)
        meta["seconds"] += out.seconds
        meta["raw"] = out.text
        meta["finish"] = getattr(out, "finish", "")
        meta["thinking_chars"] = getattr(out, "reasoning_chars", 0)
        try:
            obj = parse_json_object(out.text)
        except (ValueError, json.JSONDecodeError):
            continue
        if isinstance(obj, dict):
            meta["valid"] = True
            return guard_severity(drop_generic_character(normalize_record(obj)), text), meta
    return empty_record(), meta


def merge_records(base: dict, new: dict) -> dict:
    """Fill gaps in `base` from `new`; lists are unioned. Earlier answers win for single values."""
    merged = {k: (list(v) if isinstance(v, list) else v) for k, v in base.items()}
    for f in FIELDS:
        if f in LIST_FIELDS:
            seen = {norm(x) for x in merged[f]}
            for item in new.get(f) or []:
                if norm(item) not in seen:
                    merged[f].append(item)
                    seen.add(norm(item))
        elif f == "medication":
            names = {norm(m["name"]) for m in merged[f]}
            for med in new.get(f) or []:
                if norm(med["name"]) not in names:
                    merged[f].append(dict(med))
                    names.add(norm(med["name"]))
        elif is_empty(merged.get(f)) and not is_empty(new.get(f)):
            merged[f] = new[f]
    return merged


if __name__ == "__main__":
    import argparse

    from gemma_client import DEFAULT_BASE_URL, GemmaClient

    ap = argparse.ArgumentParser(description="Extract one description and print the JSON record.")
    ap.add_argument("text")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    args = ap.parse_args()
    rec, meta = extract(GemmaClient(args.base_url), args.text)
    print(json.dumps(rec, ensure_ascii=False, indent=2))
    print(f"\nvalid={meta['valid']} attempts={meta['attempts']} {meta['seconds']:.2f}s "
          f"finish={meta['finish']} thinking_chars={meta['thinking_chars']}")
    if not meta["valid"]:
        print("\n--- raw model output (last attempt) ---\n" + (meta["raw"] or "(empty)"))
