package dev.optrolley.autoroute.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.optrolley.autoroute.R
import dev.optrolley.core.Bench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The debug screen is offered only in a build that can be debugged. */
fun Context.isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

private const val BENCH_ASSETS = "bench"
private const val BENCH_LOG = "OptrolleyBench"

/**
 * Tools for whoever is building the app; not part of what it does (tier 4
 * §7.2). Nothing here reads or writes the user's history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Benchmark()
        }
    }
}

/**
 * Times each derivation on synthetic histories of growing length, shipped
 * with debug builds, to see when a long history would first be felt on this
 * phone (H12). Each line is also logged, to be read over adb.
 */
@Composable
private fun Benchmark() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(emptyList<String>()) }

    // A phone that dims or sleeps slows down, and the times with it.
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    SectionTitle(stringResource(R.string.benchmark))
    Text(
        stringResource(R.string.benchmark_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Button(
        enabled = !running,
        onClick = {
            running = true
            lines = listOf(Bench.header())
            scope.launch {
                try {
                    val files = context.assets.list(BENCH_ASSETS).orEmpty().sorted()
                    if (files.isEmpty()) lines = lines + context.getString(R.string.benchmark_no_histories)
                    for (file in files) {
                        val line = withContext(Dispatchers.Default) {
                            val export = context.assets.open("$BENCH_ASSETS/$file").use { String(it.readBytes(), Charsets.UTF_8) }
                            Bench.run(export, lab = "-lab" in file).line()
                        }
                        Log.i(BENCH_LOG, line)
                        lines = lines + line
                    }
                    Log.i(BENCH_LOG, "done")
                } finally {
                    running = false
                }
            }
        },
        modifier = Modifier.padding(16.dp).testTag(Tags.DEBUG_BENCH_RUN),
    ) { Text(stringResource(R.string.benchmark_run)) }
    if (running) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp))
    if (lines.isNotEmpty()) {
        SelectionContainer {
            Text(
                lines.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp).testTag(Tags.DEBUG_BENCH_OUT),
            )
        }
    }
}
