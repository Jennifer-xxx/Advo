package com.gemmahack.symptomdiary

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: WithYouViewModel, ui: UiController) {
    val activity = LocalActivity.current ?: return
    Title("Yours. On\nthis phone.", "Your personal health advocate, in your pocket.")
    Panel {
        Glyph("folder", Rust, 38)
        Text(if (vm.modelReady) "Local Gemma is ready" else "Manual records are ready", fontSize = 23.sp, fontWeight = FontWeight.Medium)
        Text("Health records and attachments are encrypted with an Android Keystore key. Automatic cloud backup and device transfer are excluded.", color = Muted, fontSize = 15.sp, lineHeight = 23.sp)
        Text("${vm.storageGb} · ${vm.data.visits.size} visits", color = Muted, fontSize = 13.sp)
    }
    EntryRow("paper", "Offline resources", if (vm.modelReady) "Local model loaded · ${vm.backend}" else "Import or retry your local model") { vm.navigate(Screen.RESOURCES) }
    EntryRow("mic", "Language & read-aloud", "English demo · input language chosen separately") { vm.navigate(Screen.LANGUAGE) }
    EntryRow("folder", "Export encrypted backup", "Choose a local file and your own password") {
        ui.fields("Encrypted local backup", listOf("Password · at least 12 characters", "Repeat password"), button = "Choose destination", password = true,
            description = "Includes all visits, audio, paper, facts and health history. Stop recording first. There is no password recovery. Choose Downloads or internal storage.") { values ->
            when { values[0].length < 12 -> vm.notice = "Use a password with at least 12 characters."
                values[0] != values[1] -> vm.notice = "The passwords do not match."
                else -> ui.exportBackup(values[0]) }
        }
    }
    EntryRow("folder", "Restore encrypted backup", "Verify before merging · existing visit IDs are kept") {
        ui.fields("Restore a backup", listOf("Backup password"), button = "Choose local backup", password = true,
            description = "After password and integrity checks, new visit IDs and their profile entries are added. Existing IDs win and settings are kept. If your index cannot be opened, its encrypted bytes are preserved in a recovery folder and the verified backup becomes the new index. Stop recording first.") { ui.restoreBackup(it[0]) }
    }
    Panel {
        CheckRow("Keep screen awake during a visit", vm.data.settings.keepScreenAwake,
            "Only while the visit companion is visible") { selected -> vm.settings { it.copy(keepScreenAwake = selected) } }
        TextButton(onClick = {
            ui.confirm("Allow screenshots for this session?", "This allows this app's visible health details to appear in screenshots, screen sharing and recent-app previews until you restart. Use synthetic data for demonstrations.", "Allow this session") {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }) { Text("Allow demo screenshots this session", color = Rust) }
    }
    Secondary("Open a synthetic demo visit") { vm.loadDemo() }
    Text("Advo organizes communication. It does not diagnose, prescribe, certify a clinician's approval or decide whether a medicine is safe.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    Text("Recordings remain until you delete them. Temporary previews and explicit exports in app cache are cleared after a fresh process starts. Files you export outside the app are yours to manage.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
}

@Composable
fun ResourcesScreen(vm: WithYouViewModel, ui: UiController) {
    Title("Ready, even\noffline", "Provision files locally before your visit. The app never downloads or calls a server.")
    Panel {
        Text(vm.modelStatus, fontSize = 18.sp, lineHeight = 25.sp)
        if (vm.modelLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Rust, trackColor = Peach)
            if (vm.progressMb > 0) Text("${vm.progressMb} MB imported · checksum verification follows", fontSize = 13.sp, color = Muted)
        }
        if (vm.modelReady) {
            Text(vm.modelName, fontSize = 14.sp)
            Text("Backend: ${vm.backend}\nAudio input: ${if (vm.audioAvailable) "initialized" else "unavailable"}\nVision: ${if (vm.visionAvailable) "initialized" else "unavailable"}", color = Muted, fontSize = 14.sp)
            Text(vm.modelFingerprint, fontSize = 11.sp, lineHeight = 17.sp, color = Muted)
        }
    }
    SectionLabel("Model preference")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(vm.data.settings.modelPreference == "E2B", { vm.settings { it.copy(modelPreference = "E2B") } }, label = { Text("E2B · lighter") }, enabled = !vm.busy && !vm.modelLoading)
        FilterChip(vm.data.settings.modelPreference == "E4B", { vm.settings { it.copy(modelPreference = "E4B") } }, label = { Text("E4B · larger") }, enabled = !vm.busy && !vm.modelLoading)
    }
    Text("Reload to apply a preference. A model is loaded once and reused; GPU initialization falls back to CPU when needed.", color = Muted, fontSize = 13.sp)
    Primary("Load / reload local model", enabled = !vm.modelLoading && !vm.busy) { vm.reloadModel() }
    Secondary("Import / update a Gemma model", enabled = !vm.modelLoading && !vm.busy) {
        ui.fields("Verify a local model", listOf("Variant · E2B or E4B", "Publisher SHA-256 · 64 hex characters"), listOf(vm.data.settings.modelPreference, ""),
            button = "Choose local .litertlm file", description = "Get the matching Gemma 4 LiteRT-LM file and checksum before the offline demo. Import streams to a temporary file, checks free space and the checksum, then replaces the chosen variant. Failed imports retain the installed file.") {
            ui.importModel(it[0].trim().uppercase(), it[1].trim())
        }
    }
    Panel {
        SectionLabel("Device")
        Text(vm.hardware, fontSize = 15.sp)
        Text("${vm.storageGb}\nThermal status: ${vm.thermal}\nPending sources: ${vm.processingQueue}", color = Muted, fontSize = 13.sp)
        Text("Pixel uses its installed on-device speech service, local Gemma GPU execution, camera and hardware-backed Keystore where available. Heavy live work pauses when the device reports severe thermal load.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Text("Tensor TPU execution requires a compatible compiled model and vendor runtime. This build reports its actual GPU/CPU backend and does not claim TPU acceleration.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
    }
    Panel {
        SectionLabel("Language resources")
        Text("Install speech and offline English read-aloud voices in device settings before the demo. Advo only uses verified installed speech or its local Gemma audio input; it never starts a language download.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Secondary("Input & language settings") { vm.navigate(Screen.LANGUAGE) }
    }
    Flag("Drug interaction checking unavailable: no validated local drug database is bundled. Literal reviewed allergy-name checks are a limited comparison, not a safety verdict.")
}

@Composable
fun LanguageScreen(vm: WithYouViewModel) {
    Title("Your language", "Voice input never changes your interface language.")
    SectionLabel("App language")
    Panel {
        CheckRow("Follow device · English demo", vm.data.settings.appLanguage == "device") { if (it) vm.settings { old -> old.copy(appLanguage = "device") } }
        CheckRow("English", vm.data.settings.appLanguage == "en") { if (it) vm.settings { old -> old.copy(appLanguage = "en") } }
        Text("This demonstration uses English throughout. Other interface translations are not claimed as validated.", color = Muted, fontSize = 13.sp)
    }
    SectionLabel("Voice input language")
    listOf("auto" to "Auto-detect · installed languages only", "en-US" to "English (United States)", "es-US" to "Spanish · validation pending", "zh-CN" to "Mandarin · validation pending").forEach { (tag, title) ->
        Panel { CheckRow(title, vm.data.settings.voiceLanguage == tag || tag == "en-US" && vm.data.settings.voiceLanguage == "en") { selected ->
            if (selected) vm.settings { it.copy(voiceLanguage = tag) }
        } }
    }
    Text("Choose the language you will use. If recognition is unclear or a pack is unavailable, choose again or type. Mixed-language medication names, negation, doses and timing require review. Original wording is always retained.", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
    Secondary(if (vm.speaking) "Pause voice check" else "Test installed offline English read-aloud") { vm.readAloud("Advo reads using an installed offline voice. Review every medicine name, dose and time condition with your clinician.") }
    Primary("Done") { vm.back() }
}
