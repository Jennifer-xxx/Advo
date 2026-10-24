@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gemmahack.symptomdiary

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

@Composable
fun App(vm: DiaryViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tx = vm.tx
    val tabs = listOf("✏️" to tx.tabRecord, "📅" to tx.tabHistory, "📄" to tx.tabReport)
    MaterialTheme {
        Scaffold(
            topBar = { StatusBar(vm) },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Text(icon) }, label = { Text(label) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
                when (tab) {
                    0 -> RecordScreen(vm)
                    1 -> HistoryScreen(vm)
                    else -> ReportScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun StatusBar(vm: DiaryViewModel) {
    val tx = vm.tx
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tx.appTitle, style = MaterialTheme.typography.titleMedium)
                Text(vm.statusText, style = MaterialTheme.typography.bodySmall)
                if (vm.timingText.isNotEmpty()) Text(vm.timingText, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = vm::switchLang, enabled = !vm.busy && !vm.recording) { Text(tx.switchLang) }
        }
    }
}

@Composable
private fun RecordScreen(vm: DiaryViewModel) {
    val tx = vm.tx
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.messages) { Bubble(it) }
            if (vm.busy) item { Text(tx.thinking, style = MaterialTheme.typography.bodySmall) }
        }

        val q = vm.question
        if (q != null && q.options.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                q.options.forEach { o ->
                    FilterChip(selected = o in vm.selected, onClick = { vm.toggle(o, q.multi) }, label = { Text(o) })
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = vm.input, onValueChange = { vm.input = it },
                modifier = Modifier.weight(1f), placeholder = { Text(tx.inputHint) }, maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = vm::send, enabled = !vm.busy && !vm.recording) { Text(tx.send) }
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    when {
                        vm.recording -> vm.stopRecording()
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> vm.startRecording()
                        else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = vm.recording || !vm.busy,
            ) { Text(if (vm.recording) tx.stop else tx.speak) }
            OutlinedButton(onClick = vm::skip, enabled = !vm.busy && vm.question != null) { Text(tx.skip) }
            Button(onClick = vm::save, enabled = !vm.busy) { Text(tx.save) }
            TextButton(onClick = vm::newEntry, enabled = !vm.busy) { Text(tx.newEntry) }
            if (vm.voiceStatus.isNotEmpty() && vm.voiceEngine == "system") {
                Text(vm.voiceStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            }
            FilterChip(
                selected = vm.voiceEngine == "system",
                onClick = vm::toggleVoiceEngine,
                enabled = !vm.recording,
                label = { Text(tx.voiceLabel + if (vm.voiceEngine == "system") tx.engineSystem else tx.engineGemma) },
            )
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val (bg, align) = when (m.role) {
        Role.USER -> MaterialTheme.colorScheme.primaryContainer to Alignment.End
        Role.BOT -> MaterialTheme.colorScheme.surfaceVariant to Alignment.Start
        Role.ALERT -> MaterialTheme.colorScheme.errorContainer to Alignment.Start
        Role.INFO -> Color.Transparent to Alignment.CenterHorizontally
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
            Text(
                if (m.role == Role.ALERT) "⚠️ ${m.text}" else m.text,
                Modifier.padding(10.dp),
                style = if (m.role == Role.INFO) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun HistoryScreen(vm: DiaryViewModel) {
    val tx = vm.tx
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::loadDemo) { Text(tx.loadDemo) }
            OutlinedButton(onClick = vm::clearAll) { Text(tx.clear) }
        }
        Text(
            tx.entryCount(vm.entries.size),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.entries.reversed()) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(e.createdAt.replace('T', ' ') + if (e.demo) tx.demoTag else "", style = MaterialTheme.typography.labelSmall)
                        Text(vm.summaryLine(e), style = MaterialTheme.typography.bodyLarge)
                        e.record.patientQuote?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportScreen(vm: DiaryViewModel) {
    val tx = vm.tx
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            listOf(7, 14, 30).forEach { d ->
                FilterChip(selected = vm.reportDays == d, onClick = { vm.reportDays = d }, label = { Text(tx.days(d)) })
            }
            Text(tx.reportLanguage, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp, top = 14.dp))
            FilterChip(selected = vm.reportLang == "zh", onClick = { vm.reportLang = "zh" }, label = { Text("中文") })
            FilterChip(selected = vm.reportLang == "en", onClick = { vm.reportLang = "en" }, label = { Text("English") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::generateReport, enabled = !vm.busy) { Text(if (vm.busy) tx.generating else tx.generate) }
            OutlinedButton(onClick = { webView?.let { printPdf(context, it) } }, enabled = vm.reportHtml != null) {
                Text(tx.exportPdf)
            }
        }

        val html = vm.reportHtml
        if (html == null) {
            Text(tx.reportEmpty, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        } else {
            OutlinedTextField(
                value = vm.narrative, onValueChange = { vm.narrative = it },
                label = { Text(tx.narrativeLabel) }, modifier = Modifier.fillMaxWidth(), maxLines = 5,
            )
            OutlinedTextField(
                value = vm.questionsForDoctor, onValueChange = { vm.questionsForDoctor = it },
                label = { Text(tx.questionsLabel) }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            )
            TextButton(onClick = vm::rerender) { Text(tx.apply) }
            AndroidView(
                factory = { ctx -> WebView(ctx).also { webView = it } },
                update = { wv ->
                    if (wv.tag != html) {
                        wv.tag = html
                        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** System print dialog; choose "Save as PDF". Works offline. */
private fun printPdf(context: Context, webView: WebView) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val name = "symptom-report"
    pm.print(
        name, webView.createPrintDocumentAdapter(name),
        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.NA_LETTER).build(),
    )
}
