"""Model-free tests. Run: python -m pytest python/tests  (or python python/tests/test_core.py)."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common import SHARED, load_knowledge, soft_match  # noqa: E402
from eval_extraction import load_testset, oracle_client, score_item, summarize  # noqa: E402
from extraction import extract, merge_records, normalize_record, parse_json_object  # noqa: E402
from gemma_client import FunctionClient  # noqa: E402
from interview import Interview, detect_red_flags, match_condition  # noqa: E402
from make_demo_data import build  # noqa: E402
from report import aggregate, render_html  # noqa: E402

K = load_knowledge()


def test_parse_json_handles_fences_and_trailing_commas():
    raw = 'Sure!\n```json\n{"symptom": "头痛", "character": ["一跳一跳的",], "note": "a } inside"}\n```'
    assert parse_json_object(raw)["character"] == ["一跳一跳的"]


def test_normalize_coerces_types():
    rec = normalize_record({"symptom": " 头痛 ", "severity_0_10": "大概 6 分", "character": "刺痛",
                            "medication": ["布洛芬"], "location": "null"})
    assert rec["symptom"] == "头痛" and rec["severity_0_10"] == 6 and rec["character"] == ["刺痛"]
    assert rec["medication"] == [{"name": "布洛芬", "effect": None}] and rec["location"] is None
    assert normalize_record({"severity_0_10": 15})["severity_0_10"] is None


def test_extract_retries_on_bad_json():
    replies = iter(["I think it is a headache.", '{"symptom": "头痛"}'])
    rec, meta = extract(FunctionClient(lambda p: next(replies)), "头疼")
    assert rec["symptom"] == "头痛" and meta["attempts"] == 2 and meta["valid"]


def test_merge_keeps_first_values_and_unions_lists():
    a = normalize_record({"symptom": "头痛", "associated": ["恶心"]})
    b = normalize_record({"symptom": "偏头痛", "associated": ["恶心", "怕光"], "severity_0_10": 6})
    m = merge_records(a, b)
    assert m["symptom"] == "头痛" and m["associated"] == ["恶心", "怕光"] and m["severity_0_10"] == 6


def test_red_flags_and_negation():
    ids = lambda t: [f["id"] for f in detect_red_flags(t, K)]
    assert ids("胸口痛，喘不上气") == ["chest_pain_with_breathing"]
    assert ids("嗓子疼，没有发烧，脖子发僵") == []
    assert ids("chest pain and I'm short of breath") == ["chest_pain_with_breathing"]
    assert ids("no chest pain, just short of breath") == []
    assert "self_harm" in ids("我真的不想活了")


def test_condition_match():
    assert match_condition({"symptom": "偏头痛"}, "", K)["id"] == "headache"
    assert match_condition({"symptom": None}, "my belly cramps", K)["id"] == "abdominal"


def test_interview_flow_with_fake_model():
    def fake(prompt: str) -> str:
        if "Rewrite this follow-up question" in prompt:
            return prompt.rsplit("Question: ", 1)[1].strip()
        if "We just asked" in prompt:
            return '{"onset": "昨天晚上"}'
        return '{"symptom": "头痛", "location": "右边太阳穴", "character": ["一跳一跳的"]}'

    iv = Interview(FunctionClient(fake))
    s = iv.start("右边太阳穴一跳一跳地疼")
    assert s["question"].slot == "severity_0_10"
    s = iv.answer(options=["6"])
    assert iv.record["severity_0_10"] == 6 and s["question"].slot == "onset"
    s = iv.answer(text="昨天晚上开始的")
    assert iv.record["onset"] == "昨天晚上"
    assert s["question"].slot == "associated" and "怕光" in s["question"].options
    s = iv.answer(options=["怕光", "恶心或呕吐"])
    assert s["question"] is None  # max 3 follow-ups
    e = iv.entry()
    assert e["condition"] == "headache" and e["record"]["associated"] == ["怕光", "恶心或呕吐"]


def test_report_on_demo_data():
    entries = build(__import__("datetime").date(2026, 10, 24))
    s = aggregate(entries, lang="zh", days=14)
    head = s["symptoms"][0]
    assert s["entry_count"] == 10 and head["name"] == "头痛 / 偏头痛" and head["entries"] == 8
    assert head["severity_max"] == 8 and {m["name"] for m in s["medications"]} == {"布洛芬", "舒马普坦"}
    html = render_html(s, "测试陈述", "zh", ["这种头痛需要做检查吗？"])
    assert "<svg" in html and "舒马普坦" in html and "测试陈述" in html
    assert "History of present illness" in render_html(aggregate(entries, lang="en"), "x", "en")


def test_oracle_scores_perfectly_and_soft_match():
    items = load_testset(SHARED / "eval" / "extraction_testset.jsonl")
    client = oracle_client(items)
    results = [{"scores": score_item(extract(client, it["text"])[0], it["expected"])} for it in items]
    for row in summarize(results):
        assert row["score"] == 1.0 and row["halluc"] == 0, row
    assert soft_match("胃里", ["胃"]) and not soft_match("疼", ["太阳穴疼"]) and soft_match("Lower back", ["lower back"])


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
