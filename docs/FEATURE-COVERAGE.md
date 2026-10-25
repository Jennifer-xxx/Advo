# Specification coverage

Source of truth: `../specifications/WITH-YOU-FEATURES-PRD.md`, `../specifications/WITH-YOU-USER-JOURNEY-PRD.md` and the four adjacent PNGs, read in full before implementation. The user's Android/Pixel, English-demo and strictly offline instructions override the PRDs' iPhone baseline and initial network-download allowance. Design examples are synthetic reference content, never prefilled patient facts.

**Implemented** means there is a real code path, not a claim of broad device or clinical validation. **Gated** means the feature is retained in the product scope with an explicit availability boundary. It never produces a substitute success or safety result. See [validation](VALIDATION.md) for executed checks.

| ID | Behavior in this implementation | Scope / verification boundary |
| --- | --- | --- |
| A01 | Voice orb home, explicit tap, type and records alternatives, no account/form prerequisite. | No microphone operation at startup. |
| A02 | Local model import with streamed MB progress, storage checks, supplied SHA-256 and atomic commit; installed-resource status. | Network downloads replaced by offline provisioning. Speech/TTS packs must already be installed. |
| A03 | Encrypted atomic Visit/source/fact/report storage, draft text, interrupted-state recovery, legacy migration. | Native device checks cover persistence and recovery; active audio chunk remains bounded volatile work. |
| A04 | Just-in-time microphone/camera requests; denial preserves text/import routes. | Physical camera/microphone denial/interruption matrix still requires manual acceptance. |
| A05 | Literal recording, paused, interrupted, queue, processing, draft, unresolved and saved states. | Orb color is never the only state indicator. |
| A06 | AES-GCM, Android Keystore, backup exclusions, screen protection, no application health logging, suppressed native model logs. | Hardware key backing follows device capability. Private PDF scratch and explicit export cache are documented. |
| A07 | User-chosen local update with checksum/version/filename provenance and failure-preserving replacement. | No automatic update or cloud fallback. |
| B01 | Persistent Follow device / English preference; the demonstration stays English. | Additional localized interfaces are an explicit English-demo adaptation, not a validated multilingual release. |
| B02 | Installed-language check, explicit language choice, Android 14+ installed-only auto-detection/switching and uncertain-language prompt; Gemma original-language audio. | Language detection depends on the on-device speech service. No arbitrary-language support claim. |
| B03 | Input language independent of interface; English doctor view/translation draft; original wording accessible. | Untranslated original quotes remain visible rather than being passed off as verified English. |
| B04 | Text alternatives, offline-only English read-aloud, stop/pause controls. | Speech refuses a missing or network-required voice; no read-aloud while actively recording. |
| B05 | Native scalable text, scrolling layouts, labeled voice/navigation controls, 48+ dp principal touch targets, no color-only review flags. | Full TalkBack/large-font/device accessibility audit remains a gate. |
| B06 | Typed fields, exact source quotes, numeric/name/unit guards, independent translation review. | Multilingual drug-name, negation and timing fidelity needs a representative multilingual evaluation set. |
| C01 | Free voice/text, extract then ask one question, skip, stop, correct, finish early. | Model unavailable/invalid output leaves a manual path and original data. |
| C02 | Symptom, record and goal facts with original wording, source, uncertainty and review. | No automatic diagnosis or manufactured complete history. |
| C03 | Existing current profile shown for relevance review and snapshotted into reports. | Historical entries excluded from current profile; dates and source context remain visible. |
| C04 | Patient versus suggested questions, explicit suggestion selection, edit/remove/reorder. | Suggested questions start unselected and cannot become patient opinions by inference. |
| C05 | Three concise review groups, read-aloud, corrections and draft continuation. | Unknown details do not block report creation. |
| C06 | Native selectable-text Letter PDF, three sections, four record subgroups, empty question boxes, patient wording highlight and continuation pages. | Native normal/overflow PDF checks plus external text/render QA. |
| C07 | Local preview/zoom/save, explicit Android share and print. | External destination chosen by the user may have network access; strictly offline demo uses a local file. |
| C08 | Incrementing immutable structured report snapshots with model/prompt provenance. | Changes to current profile or visit do not rewrite prior report versions. |
| D01 | Everyone agreed starts unchecked for each consent screen; code requires explicit agreement and service rechecks persisted consent. Manual route and withdrawal. | Consent records a patient action, not a legal certification. |
| D02 | Microphone foreground service, stop notification, pause/resume/stop, visible timer, encrypted 12-second segments and interruption recovery. | Full long-session, call, lock-screen and battery testing remains required. |
| D03 | Local Gemma audio transcript linked to each segment's time range, unknown speaker, initial transcription retained after edits. | Real noisy and overlapping clinical audio accuracy is unvalidated. |
| D04 | Actual saved chunks feed a bounded quiet suggestion queue; Asked/Later/Dismiss; no automatic spoken interruption. | Model latency creates a visible backlog. No simulated live response or automatic resolution. |
| D05 | Local English large-type doctor screen with reason and explicit “nothing sent” scope. | This is not a network share or clinician account. |
| D06 | Literal reviewed medicine/allergy-name comparison with recorded reaction, reasons and original quotes. | No name normalization, drug-class inference or comprehensive contraindication coverage is claimed. |
| D07 | **Gated:** explicit unavailable interaction coverage. | No validated local drug knowledge base is installed. A model's memory is never substituted for one. |
| D08 | Discussion, medicine changes, next steps, unanswered questions; per-item corrections and patient-reported doctor check; unresolved finish. | No blanket “doctor confirmed everything” action. |
| D09 | Manual notes, question list, records, teach-back and paper import work without recording. | Native model availability does not block manual use. |
| E01 | Saved-segment local transcription, retries, source-linked extraction and draft explanation. | Failed/queued processing stays visible; original audio remains. |
| E02 | Full-resolution camera output, orientation-aware preview, retained originals, crop-a-copy, retake, multipage image/PDF import. | Local providers only; 32 MB/200-page file limits. |
| E03 | Gemma vision reads original text, then strict extraction creates draft facts. | Vision must initialize; blurry/unclear fields require manual review. Single synthetic Pixel paper read tested. |
| E04 | Page and segment originals, memory audio playback, parallel conflicting wording, keep/correct/unresolved choices. | Choosing a source is a patient preference, not medical adjudication. |
| E05 | Plain-English draft, English translation drafts, original switch, offline read-aloud, numeric/structured-field guards. | Semantic/clinical faithfulness is a quality gate; every generated explanation remains reviewable. |
| E06 | Source-grounded action facts, verbatim known timing and explicit unknown timing. | No invented follow-up dates. |
| E07 | Summary/original/reviewed selection, per-fact profile opt-in, retained draft/unresolved state and idempotent promotion. | Review of unclear wording never erases its uncertainty. |
| E08 | Patient-recollection text and voice notes, with paper as a separate source type. | Recollection is never labeled a doctor's recorded statement. |
| E09 | Explicit local date/time choice, saved time zone, notification permission and inexact alarm; cancel support. | Not an exact deadline guarantee; reopen after reboot to reschedule. |
| F01 | Orb-free health story and visit timeline, Visits/Health branches. | Synthetic demo adds a separate stable-ID visit without replacing data. |
| F02 | Current medicines, allergies/reactions, past medicines/history; unknown/not recorded/reported none. | Empty never implies no allergy. Patient “none” statements retain a local source. |
| F03 | Voice/text/photo additions; explicit promotion; correction replacement link, change history and historical status. | Unreviewed/uncertain facts cannot become current profile entries. |
| F04 | Visit details expose report versions, sources, transcripts, explanation, questions, consent and generation history. | Doctor check provenance remains a patient report. |
| F05 | Explicitly scoped local exports; separate audio/explanation deletion and full visit deletion with confirmation. | Profile/state provenance is retained when deleting its originating visit. User-exported files/backups require separate management. |
| F06 | Password-encrypted portable local backup, authenticated/validated staged restore, stable-ID merge and unreadable-index recovery. | No cloud backup or password recovery. Native wrong-password/tamper/idempotency checks included. |

## Release gates retained

Before use beyond a synthetic hackathon demonstration: validate speech/OCR/translation on representative patient language, medication and uncertainty cases; clinically review patient-facing copy; evaluate real conversations and conflicts; test long recordings, device interruptions, storage exhaustion and thermal/power behavior; perform complete accessibility checks. A broad drug checker requires an appropriate licensed, versioned local reference dataset and its own validation. A Tensor TPU path requires compatible compiled artifacts and vendor libraries.

These are not removed features and are not represented by fake successful controls. The application exposes the limited working capability and a useful offline manual route until each gate is met.
