package com.gemmahack.symptomdiary

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private var model: WithYouViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val vm: WithYouViewModel = viewModel()
            model = vm
            androidx.compose.runtime.LaunchedEffect(Unit) {
                openVisitIntent(intent, vm)
            }
            App(vm)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        model?.let { openVisitIntent(intent, it) }
    }
    private fun openVisitIntent(intent: Intent, vm: WithYouViewModel) {
        intent.getStringExtra("visit")?.let { id -> if (vm.data.visits.any { it.id == id })
            vm.selectVisit(id, if (intent.getBooleanExtra("live", false)) Screen.LIVE else Screen.DETAIL) }
    }
    override fun onStop() { model?.onBackground(); super.onStop() }
}
