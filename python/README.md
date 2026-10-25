# Historical extraction research

These local-only tools evaluate the earlier CareLog symptom diary. Their prompts, knowledge file, demo diaries, HTML layouts and scored results are retained as research evidence. They are **not** the Advo Android runtime, are not bundled with the APK, and do not certify the new visit pipeline.

Run the existing model-free regression suite from the repository root:

```bash
python3 python/tests/test_core.py
```

For model evaluation, run a local LiteRT-LM or llama.cpp server and point `eval_extraction.py` at its loopback URL. Do not use remote inference for patient material. The earlier keyword red-flag rules are historical, unvalidated research and are not used by the current Android application. New product extraction uses `shared/runtime/prompts/withyou/prepare-v2.txt` and the Kotlin `ExtractionContract` instead.
