"""Write 14 days of synthetic diary entries for the demo persona (no real patient data).

Persona: 34, recurring headaches, sometimes poor sleep. Chinese and English versions. Dates are relative to --end.
"""
from __future__ import annotations

import argparse
import json
from datetime import date, datetime, timedelta

from common import SHARED, empty_record

# (days before end, time, raw text, record fields, condition)
ENTRIES = [
    (13, "08:40", "早上醒来右边太阳穴一跳一跳地疼，昨晚只睡了五个小时。大概 5 分。",
     dict(symptom="头痛", location="右侧太阳穴", onset="早上醒来", character=["一跳一跳的"], severity_0_10=5,
          aggravating=["没睡好"], patient_quote="右边太阳穴一跳一跳地疼"), "headache"),
    (11, "15:20", "下午开会的时候头又疼了，盯着屏幕更难受，有点怕光，吃了布洛芬，一个小时后好一点。6 分。",
     dict(symptom="头痛", onset="下午开会的时候", severity_0_10=6, aggravating=["盯着屏幕"], associated=["怕光"],
          medication=[{"name": "布洛芬", "effect": "一个小时后好一点"}], patient_quote="盯着屏幕更难受"), "headache"),
    (10, "22:10", "很累，没劲，躺下一个多小时才睡着。",
     dict(symptom="乏力", duration=None, character=["没劲"], associated=["入睡困难"],
          patient_quote="躺下一个多小时才睡着"), "fatigue"),
    (8, "07:50", "头痛，这次是左边，像被箍住一样，还恶心，吐了一次。7 分。",
     dict(symptom="头痛", location="左侧", character=["像被箍住一样"], severity_0_10=7, associated=["恶心", "呕吐"],
          patient_quote="像被箍住一样"), "headache"),
    (7, "18:30", "头还是有点疼，比昨天好，3 分，喝了点热水休息了一下。",
     dict(symptom="头痛", severity_0_10=3, relieving=["休息"], patient_quote="比昨天好"), "headache"),
    (5, "13:00", "中午出门晒太阳以后开始头痛，一跳一跳的，怕光怕吵，吃了舒马普坦，半小时后明显缓解。8 分。",
     dict(symptom="头痛", onset="中午出门以后", character=["一跳一跳的"], severity_0_10=8, aggravating=["晒太阳"],
          associated=["怕光", "怕吵"], medication=[{"name": "舒马普坦", "effect": "半小时后明显缓解"}],
          patient_quote="晒太阳以后开始头痛，一跳一跳的"), "headache"),
    (4, "23:00", "又失眠了，脑子停不下来，到一点多才睡着。",
     dict(symptom="失眠", character=["脑子停不下来"], patient_quote="脑子停不下来"), "fatigue"),
    (2, "09:15", "早上起来头有点昏，2 分，没吃药。",
     dict(symptom="头痛", onset="早上起来", character=["昏"], severity_0_10=2, patient_quote="头有点昏"), "headache"),
    (1, "16:40", "下午头痛又来了，右边太阳穴，屏幕看久了，6 分，吃了布洛芬没什么用。",
     dict(symptom="头痛", location="右侧太阳穴", onset="下午", severity_0_10=6, aggravating=["屏幕看久了"],
          medication=[{"name": "布洛芬", "effect": "没什么用"}], patient_quote="吃了布洛芬没什么用"), "headache"),
    (0, "10:30", "今天早上又有一点头痛，3 分，脖子和肩膀发紧。",
     dict(symptom="头痛", onset="今天早上", severity_0_10=3, associated=["脖子和肩膀发紧"],
          patient_quote="脖子和肩膀发紧"), "headache"),
]

# Same persona and timeline in English, for demos to English-speaking judges.
ENTRIES_EN = [
    (13, "08:40", "Woke up with a throbbing pain in my right temple. Only slept five hours last night. About a 5.",
     dict(symptom="headache", location="right temple", onset="on waking", character=["throbbing"], severity_0_10=5,
          aggravating=["poor sleep"], patient_quote="a throbbing pain in my right temple"), "headache"),
    (11, "15:20", "Headache came back in an afternoon meeting, worse staring at the screen, a bit sensitive to light. Took ibuprofen, a little better after an hour. 6.",
     dict(symptom="headache", onset="during an afternoon meeting", severity_0_10=6, aggravating=["staring at the screen"],
          associated=["sensitive to light"], medication=[{"name": "ibuprofen", "effect": "a little better after an hour"}],
          patient_quote="worse staring at the screen"), "headache"),
    (10, "22:10", "So tired, no energy. Took over an hour to fall asleep.",
     dict(symptom="fatigue", character=["no energy"], associated=["trouble falling asleep"],
          patient_quote="Took over an hour to fall asleep"), "fatigue"),
    (8, "07:50", "Headache, left side this time, like a tight band around my head. Nauseous, threw up once. 7.",
     dict(symptom="headache", location="left side", character=["like a tight band around my head"], severity_0_10=7,
          associated=["nausea", "vomiting"], patient_quote="like a tight band around my head"), "headache"),
    (7, "18:30", "Still a bit of a headache, better than yesterday, a 3. Drank some warm water and rested.",
     dict(symptom="headache", severity_0_10=3, relieving=["rest"], patient_quote="better than yesterday"), "headache"),
    (5, "13:00", "Headache started after I walked out into the sun at noon, throbbing, light and noise both bother me. Took sumatriptan, much better after half an hour. 8.",
     dict(symptom="headache", onset="after going out at noon", character=["throbbing"], severity_0_10=8, aggravating=["sunlight"],
          associated=["sensitive to light", "sensitive to noise"], medication=[{"name": "sumatriptan", "effect": "much better after half an hour"}],
          patient_quote="light and noise both bother me"), "headache"),
    (4, "23:00", "Can't sleep again, my mind won't stop. Didn't fall asleep until after 1 am.",
     dict(symptom="insomnia", character=["my mind won't stop"], patient_quote="my mind won't stop"), "fatigue"),
    (2, "09:15", "Head feels a bit foggy this morning, a 2. No medicine.",
     dict(symptom="headache", onset="this morning", character=["foggy"], severity_0_10=2, patient_quote="Head feels a bit foggy"), "headache"),
    (1, "16:40", "Headache again this afternoon, right temple, after too long on the screen. 6. Ibuprofen didn't help.",
     dict(symptom="headache", location="right temple", onset="this afternoon", severity_0_10=6, aggravating=["long screen time"],
          medication=[{"name": "ibuprofen", "effect": "didn't help"}], patient_quote="Ibuprofen didn't help"), "headache"),
    (0, "10:30", "A little headache again this morning, a 3, and my neck and shoulders feel tight.",
     dict(symptom="headache", onset="this morning", severity_0_10=3, associated=["tight neck and shoulders"],
          patient_quote="my neck and shoulders feel tight"), "headache"),
]


def build(end: date, lang: str = "zh") -> list[dict]:
    out = []
    source = ENTRIES_EN if lang == "en" else ENTRIES
    for i, (back, hhmm, raw, fields, cond) in enumerate(source):
        rec = empty_record()
        rec.update(fields)
        day = end - timedelta(days=back)
        out.append({
            "id": f"demo{lang}{i:02d}",
            "created_at": f"{day.isoformat()}T{hhmm}",
            "lang": lang,
            "raw_text": raw,
            "record": rec,
            "condition": cond,
            "red_flags": [],
            "followups": 0,
            "demo": True,
        })
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--end", default="2026-10-24", help="date of the newest entry (YYYY-MM-DD)")
    args = ap.parse_args()
    end = datetime.strptime(args.end, "%Y-%m-%d").date()
    for lang, name in (("zh", "demo_entries.json"), ("en", "demo_entries_en.json")):
        data = build(end, lang)
        path = SHARED / "demo" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"wrote {len(data)} entries to {path}")
