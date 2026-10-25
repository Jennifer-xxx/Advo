# Validation record — October 24, 2026

The Android application compiled successfully and Android lint completed with **zero errors** before the final branding-only edit. The unit suite passed **22 tests** covering consent, uncertainty, source grounding, numeric-field validation, profile promotion/corrections, immutable snapshots and recovery. The historical Python suite also passed its 9 checks.

On the connected **Pixel 11 Pro / Tensor G6**, isolated synthetic device checks passed for network/backup manifest restrictions, encrypted durable storage, attachment authentication, recording/processing restart recovery, portable backup and idempotent restore, wrong-password rejection, tamper rejection and native PDF pagination. A local Gemma E4B GPU call passed the source-grounded extraction contract and read a synthetic instruction-sheet image. This was **9 passing device checks**, not a clinical evaluation.

Observed single-run timings: E4B load 51.3 s; extraction 29.2 s; synthetic image reading 4.4 s. Audio and vision backends initialized. E2B has also been provisioned locally on the Pixel as the preferred lighter option; no E2B benchmark is asserted here.

Native PDF QA: normal sample was one selectable-text US Letter page; overflow sample retained content across seven pages. The normal report was rendered and visually inspected. The Android emulator was put in airplane mode; app startup and native navigation worked without a model. Full consented microphone, camera, noisy/overlapping speech, sustained recording, multilingual fidelity, low-storage, external share/print and accessibility acceptance are still required.

A later isolated check for recovery from an unreadable index is included in the test runner but is not counted among the executed device checks above. No user health repository was opened by instrumentation and no microphone audio was recorded by those checks.

Reproduce build, unit tests, lint and optional synthetic device checks using the README commands. Broad drug interaction checking and Tensor TPU execution remain explicitly unavailable until their required local data/runtime artifacts are supplied and validated.
