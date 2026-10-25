# Advo: Your personal health advocate, in your pocket.

Advo is a private, on-device health companion powered by Gemma. It helps patients turn symptoms into clear appointment briefs, understand medical conversations and ask follow-up questions, and organize their health records in one place.

**Why Gemma:** Running on-device, Gemma keeps health processing private on the phone and lets Advo work offline, even in rural clinics. Its multimodal understanding handles voice, text and images in one model.

“You must be your own best advocate,” but in a 15-minute visit most patients can’t. Symptoms are hard to put into words, important connections get missed in the moment, and health history is scattered everywhere.

Advo helps patients speak clearly, connect the dots, and keep their whole health story:

- **Before:** Patients describe symptoms by voice in their own words. Advo asks short follow-ups about onset, severity, duration and change, then creates a one-page brief for the doctor, with continuation pages when needed.
- **During:** With consent, Advo listens, tracks unanswered questions and surfaces questions about new prescriptions and the patient’s saved records. It never diagnoses. The current demo supports exact reviewed medicine/allergy-name comparisons; comprehensive medication-interaction checking requires a validated local reference database and is explicitly unavailable.
- **After:** Each visit stays in a personal health Library: medications, allergies, appointments, source papers and reports. Patients can explicitly export and share a report with a caregiver; nothing is sent automatically.

## Techniques used

- **Gemma 4 + LiteRT-LM:** one local multimodal model for audio transcription, document reading, grounded extraction, follow-up questions, translation and plain-language drafts.
- **Pixel execution:** local GPU inference with CPU fallback, reused engine, bounded context/output, speculative decoding where supported, installed on-device dictation, offline TTS and thermal-aware processing.
- **Native Android:** Kotlin, Jetpack Compose, coroutines, a microphone foreground service and encrypted 12-second recording segments.
- **Grounded state:** strict JSON validation, verbatim source quotes, separate uncertainty/review/consent states, immutable report snapshots and explicit profile updates.
- **Privacy:** no application network permission, Android Keystore AES-256-GCM storage, atomic writes, backup exclusions and user-directed password-encrypted backups.
- **Local documents:** orientation-aware image import/cropping, multipage PDF reading and selectable-text Letter reports with native share/save/print.

Built for the Gemma Hardware Hackathon **Mobile AI** track, targeting **Google Pixel 11 Pro** with **Gemma 4 and LiteRT-LM**. The English demo follows the two PRDs and four reference designs in [`specifications/`](specifications/). The current specifications supersede the earlier CareLog symptom-diary workflow. The application ID stays `com.gemmahack.symptomdiary` so updating an existing installation preserves its data and model directory.

## The product

| Journey | Working flow |
| --- | --- |
| Before | Tap to talk or type; one question at a time; skip, pause and correct; source-linked symptom, medicine, allergy and history drafts; choose and order questions; review; create an English, selectable-text US Letter PDF; preview, save, share or print. Reports keep immutable versions. |
| During | Per-recording **Everyone agreed** starts unchecked. Record only after consent and microphone permission, or continue with manual notes. Pause/resume/stop/withdraw; encrypted audio segments; genuine queued local transcription and quiet contextual question suggestions. **Show doctor** is a large English view on this phone. |
| Before leaving | Review discussion, medicine changes, next steps and unanswered questions individually. Patient review and a patient's statement that they checked with the doctor are distinct. Finish with unresolved details still flagged. |
| After | Capture or import local images and multipage PDFs; retain originals; rotate using photo orientation, zoom, crop a copy and retake; local Gemma document transcription and extraction; compare source wording; correct or leave unresolved; review a plain-English explanation and actions. Choose what to archive and which reviewed facts to add to the current profile. |
| Health story | Visit timeline; separate current medicines, allergies, past medicines and medical history; unknown versus patient-reported none; source/history views; explicit profile corrections and historical status; scoped exports/deletions; password-encrypted local backup and verified, idempotent restore; optional local action reminders. |

No account, EHR connection, cloud inference, analytics or remote health storage is involved. Model and language availability are shown honestly. Missing resources never block manual notes or existing records.

## Hard offline boundary

- The merged APK excludes `INTERNET` and `ACCESS_NETWORK_STATE`. No HTTP client, cloud API, hosted model or telemetry is used by the application.
- **Models are provisioned locally**, by USB or from local device storage. Import checks free space and a supplied publisher SHA-256, then atomically replaces the selected model. A failed import preserves the installed model. No in-app model or language downloads are started.
- Pixel dictation uses `createOnDeviceSpeechRecognizer` only, after installed-language verification. Missing support offers Gemma audio or text. Read-aloud refuses network-required or uninstalled voices; it has no default online fallback.
- Images, PDFs, model imports, backups and restores accept known local document providers only. Pick **Downloads** or **internal storage**. Cloud providers are rejected even if they appear in the Android picker.
- Reports are generated locally. An explicit system share or print action can hand a PDF to a user-selected external application; that application's network behavior is outside Advo. Canceling does not delete the report. For a strictly offline demo, save locally or use Android's local PDF printer.

The PRDs' initial downloads and iPhone baseline are adapted to the user's stricter no-cloud Android/Pixel requirements. All demo UI and explanations are English. Other voice inputs and English translations are available as reviewable, device-dependent capabilities; multilingual clinical fidelity is **not** asserted as validated. See the complete [feature coverage and remaining validation gates](docs/FEATURE-COVERAGE.md).

## Run on the Pixel

Use **JDK 21**, Android SDK 36 and a USB-connected, unlocked Pixel with USB debugging enabled. Do not use Android Studio's bundled JDK 25 with this Gradle version.

```bash
cd android
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle may need network access to provision development dependencies. That is separate from the installed application's runtime. The Gradle wrapper and dependency versions are pinned.

Provision a compatible model on your development computer, accepting its publisher's terms. Official model listings: [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) and [Gemma 4 E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm). Use a `.litertlm` file, not a GGUF file.

```bash
adb shell mkdir -p /sdcard/Android/data/com.gemmahack.symptomdiary/files
adb push /path/to/gemma-4-E4B-it.litertlm /sdcard/Android/data/com.gemmahack.symptomdiary/files/
adb shell am start -n com.gemmahack.symptomdiary/.MainActivity
```

Alternatively copy the model into Downloads and use **Settings → Offline resources → Import / update**, with the publisher's SHA-256. E2B is preferred for its smaller footprint; E4B can be selected for a larger model. The other variant is used if the preferred file is absent. Sideloaded models without an app-verified publisher checksum are identified explicitly.

Before the demo, provision the desired Pixel offline speech pack and an offline English TTS voice in Android's settings. Advo will not download either. Then enable airplane mode and use **Settings → Open a synthetic demo visit**. Demo content is labeled and never replaces other visits.

### Pixel-specific behavior

The app uses Pixel's installed on-device speech service, GPU-backed LiteRT-LM, a reused local engine, bounded inference context/output and GPU speculative decoding where supported. Audio and vision initialize only when supported by the selected model/runtime. Model load and processing durations, queue length, device/SOC and thermal status are visible under Offline resources. Severe thermal status defers live processing while recording keeps saving.

The connected hardware reports **Pixel 11 Pro / Tensor G6**. GPU/CPU fallback reports the backend actually initialized. **This build does not claim Tensor TPU execution.** That requires separately provisioned compatible compiled weights and vendor runtime libraries; their absence cannot become a cloud fallback. Use the [official LiteRT-LM Android guide](https://developers.google.com/edge/litert-lm/android) and [Google Tensor documentation](https://developers.google.com/edge) for that integration.

## Safety and data design

Gemma transcribes audio, reads papers, extracts grounded facts, asks one preparation question, proposes optional visit questions, translates and explains supplied facts. Application code owns permissions, consent, state transitions, persistence, profile changes, sharing and deletion. The model has no tools that can perform those actions.

Extraction responses must pass a strict contract: allowed keys and enums, string/boolean types, actual source IDs, verbatim source quotes and literal supporting medication fields. Invalid output retries once, then preserves the source for retry/manual editing. Model output never becomes a reviewed fact automatically. Transcription and OCR can still make errors; every extracted clinical detail starts as a draft.

**Drug safety has a narrow, explicit boundary.** A reviewed allergy with a recorded reaction and an exactly matching reviewed medicine name can produce a question to check with the clinician. It is not a drug-class matcher, diagnosis, contraindication engine or prescription-safety verdict. Broad interaction checking remains unavailable because no validated, licensed local drug database is bundled. The UI always explains this missing coverage; no result never means safe. The earlier prototype's unreviewed keyword emergency rules are not used by the Android product.

`Visit` links sources, facts, questions, consent, report snapshots, processing generations and reminders. Stage, recording state, processing status and fact review status are separate. Every generation records its model, prompt version, sources and duration. Corrections preserve earlier wording and sources. Only explicitly chosen, reviewed profile facts are promoted; stable fact IDs prevent repeated saves from duplicating them. A correction can move an earlier entry into history and link its replacement.

Health records and attachments use **AES-256-GCM**, atomic writes and a non-exportable Android Keystore key. Hardware backing depends on the device; the app does not claim StrongBox use. Data resides in `noBackupFilesDir`; cloud backup and device transfer are excluded. Existing `diary.json` is migrated with its full original payload and removed only after encrypted storage commits. An unreadable store is preserved and cannot be overwritten by ordinary edits. Health contents are not written to application debug logs, and native model logging is suppressed.

Recording runs in a microphone foreground service with an ongoing Stop notification. Twelve-second PCM/WAV chunks are encrypted before their references commit. Pauses, focus loss, silencing and process recovery preserve committed segments and expose interruption state. Up to the active 12-second in-memory chunk can be lost on abrupt process death; arbitrary long-session durability and battery/thermal behavior need sustained device testing. Android may stop the app under resource pressure.

Original audio can be played from memory without a plaintext audio file. PDF rendering requires a seekable private temporary file, removed in `finally` and at fresh process startup. Explicit PDF/export copies temporarily exist in private cache; they are cleared on a fresh process start. Exported device files and password-protected backups remain under the user's control. Screen capture/recent-app previews are protected by default; synthetic demo screenshots can be enabled explicitly for one session.

Backups use a separate **PBKDF2-HMAC-SHA256 (210,000 rounds), AES-256-GCM** password envelope. Restore verifies authentication, schema, paths, limits, source references and attachment completeness before committing. Existing visit/profile IDs win on merge; settings are preserved. Wrong passwords and corrupted backups do not alter the live index. Limits: 32 MB document import, 200 PDF pages per file, 40 MB per backup entry, 4 GB expanded backup. Reminders use user-entered date/time, a saved zone and inexact local alarms; delivery is approximate and reminders are rescheduled on app startup, not guaranteed across reboot until reopened.

## Repository

```text
android/                        native Kotlin / Jetpack Compose application
  app/src/main/java/.../        domain, encrypted store, local AI, recording, files, exports, screens
  app/src/test/                 pure safety, contract and state tests
  app/src/androidTest/          isolated on-device storage/backup/PDF/Gemma checks
shared/runtime/prompts/withyou/  versioned product prompts; the only shared assets packaged by Android
docs/                          feature traceability, validation record and demo instructions
scripts/                       reproducible local validation helpers
python/                        historical symptom-extraction evaluation tools, development only
shared/{prompts,knowledge,...}/ historical evaluation inputs; not bundled in the Android application
```

The historical Python tools/results describe the former symptom diary and its old prompts, not the new product's clinical or model quality. They remain useful for regressions and prompt research. Run `python3 python/tests/test_core.py`; see [their scope](python/README.md). There is one current Android visit pipeline; the obsolete diary UI, duplicate Android interview/report logic and bilingual UI string switch have been removed.

## Validation

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Isolated synthetic stores; does not read or clear the user's repository.
adb shell am instrument -w com.gemmahack.symptomdiary.test/com.gemmahack.symptomdiary.OfflineDeviceChecks
# Also initialize the local model and test grounded extraction and a synthetic paper image:
adb shell am instrument -w -e gemma true com.gemmahack.symptomdiary.test/com.gemmahack.symptomdiary.OfflineDeviceChecks
```

Read [VALIDATION.md](docs/VALIDATION.md) for actual results and unresolved device/quality gates. Test pass counts do not establish clinical validation. Advo is a communication prototype intended for the hackathon demonstration, not a clinical decision system.
