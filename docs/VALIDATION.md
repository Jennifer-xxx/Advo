# Validation record — October 24, 2026

The Android application compiled successfully and Android lint completed with **zero errors** before the final branding-only edit. The unit suite passed **22 tests** covering consent, uncertainty, source grounding, numeric-field validation, profile promotion/corrections, immutable snapshots and recovery. The historical Python suite also passed its 9 checks.

On the connected **Pixel 11 Pro / Tensor G6**, isolated synthetic device checks passed for network/backup manifest restrictions, encrypted durable storage, attachment authentication, recording/processing restart recovery, portable backup and idempotent restore, wrong-password rejection, tamper rejection and native PDF pagination. A local Gemma E4B GPU call passed the source-grounded extraction contract and read a synthetic instruction-sheet image. This was **9 passing device checks**, not a clinical evaluation.

Observed single-run timings: E4B load 51.3 s; extraction 29.2 s; synthetic image reading 4.4 s. Audio and vision backends initialized. E2B has also been provisioned locally on the Pixel as the preferred lighter option; no E2B benchmark is asserted here.

Native PDF QA: normal sample was one selectable-text US Letter page; overflow sample retained content across seven pages. The normal report was rendered and visually inspected. The Android emulator was put in airplane mode; app startup and native navigation worked without a model. Full consented microphone, camera, noisy/overlapping speech, sustained recording, multilingual fidelity, low-storage, external share/print and accessibility acceptance are still required.

A later isolated check for recovery from an unreadable index is included in the test runner but is not counted among the executed device checks above. No user health repository was opened by instrumentation and no microphone audio was recorded by those checks.

Reproduce build, unit tests, lint and optional synthetic device checks using the README commands. Broad drug interaction checking and Tensor TPU execution remain explicitly unavailable until their required local data/runtime artifacts are supplied and validated.


## Conversation and record-structure update

Added tests for one patient-only dictation across multiple turns, duplicate callbacks, finish idempotency, pause/resume callback invalidation, symptom/visit separation, selected-history snapshots and additive storage migration. Brief regressions cover `5/10` versus `5 out of 10`, word-number/decimal forms, invented values, changed medicine units, preservation of every character during batching, removal of duplicate payload metadata and interrupted processing recovery.

The updated build and lint are checked locally. A separate optional `-e conversation true` instrumentation case exercises two cached Gemma replies, a whole-dictation summary and a combined history brief without recording ambient audio. The Pixel disconnected before this new case could be executed, so no new E2B-versus-E4B latency or end-to-end microphone claim is made for this revision. The earlier E4B timings above are historical single-run measurements, not this revision's latency promise.


Final local verification for the conversation/history revision: **44 unit tests passed; Android build and lint passed with zero lint errors**. The updated application also passed **9 isolated offline emulator checks** for manifest restrictions, encrypted persistence/authentication, recording recovery, backup round-trip/idempotency, wrong passwords, unreadable-index recovery, tampering and native Letter/overflow PDF rendering. Emulator UI inspection confirmed separate Talk / Library / Visits navigation and a direct Start visit recording button.

The reported minute-long processing path was traced to a generic label spanning summary plus automatic structured extraction/retry, unspecified routine thinking configuration, and persisted processing states that could survive an interrupted job. The revision removes automatic extraction after Done, explicitly disables thinking for routine inference, makes interrupted jobs retryable, names/times the current operation and exposes native generation cancellation. These code and regression results do not substitute for a fresh Pixel latency measurement.
