package dev.optrolley.autoroute.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.optrolley.autoroute.AppViewModel
import java.io.IOException
import java.time.LocalDate

enum class Screen { LIST, STORES, ROUTING }

@Composable
fun OptrolleyApp(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.LIST) }
    val snackbar = remember { SnackbarHostState() }
    val resolver = LocalContext.current.contentResolver

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    // The system file picker grants access to the one file chosen, so no
    // storage permission is needed (tier 4 §7).
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            vm.exportTo { text ->
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("The file could not be opened")
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importFrom {
                val input = resolver.openInputStream(uri) ?: throw IOException("The file could not be opened")
                input.use { String(it.readBytes(), Charsets.UTF_8) }
            }
        }
    }

    val message = ui.message
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message.text,
            actionLabel = if (message.undo != null) "Undo" else null,
            duration = if (message.undo != null || message.text.length > 60) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        vm.messageShown(message)
        if (result == SnackbarResult.ActionPerformed) vm.undo(message)
    }

    Surface(Modifier.fillMaxSize().tagsAsResourceIds(), color = MaterialTheme.colorScheme.background) {
        val state = ui.state
        val presentation = ui.presentation
        when {
            ui.fatal != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "The saved history could not be read, so nothing will be recorded until it is fixed.\n\n${ui.fatal}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            state == null || presentation == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                BackHandler(enabled = screen != Screen.LIST) { screen = Screen.LIST }
                when (screen) {
                    Screen.LIST -> ListScreen(
                        state = state,
                        presentation = presentation,
                        focus = ui.focus,
                        snackbar = snackbar,
                        vm = vm,
                        onOpen = { screen = it },
                        onExport = { exporter.launch("optrolley-${LocalDate.now()}.json") },
                        onImport = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    )
                    Screen.STORES -> StoresScreen(state = state, snackbar = snackbar, vm = vm, onBack = { screen = Screen.LIST })
                    Screen.ROUTING -> RoutingScreen(
                        state = state,
                        lab = ui.lab,
                        snackbar = snackbar,
                        vm = vm,
                        onBack = { screen = Screen.LIST },
                    )
                }
            }
        }
    }
}
