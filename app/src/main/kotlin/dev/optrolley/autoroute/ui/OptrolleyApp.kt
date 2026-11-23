package dev.optrolley.autoroute.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.Buzz
import dev.optrolley.autoroute.R
import dev.optrolley.autoroute.scan.Scanner
import java.io.IOException
import java.time.LocalDate

enum class Screen { LIST, STORES, ROUTING, LIBRARY, SETTINGS, DEBUG }

/** [scanner] is null where there is no camera; scanning is then not offered (O46). */
@Composable
fun OptrolleyApp(vm: AppViewModel, scanner: Scanner? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.LIST) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resolver = context.contentResolver

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    // The system file picker grants access to the one file chosen, so no
    // storage permission is needed (tier 4 §7).
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            vm.exportTo { text ->
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException(context.getString(R.string.file_unopenable))
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importFrom {
                val input = resolver.openInputStream(uri) ?: throw IOException(context.getString(R.string.file_unopenable))
                input.use { String(it.readBytes(), Charsets.UTF_8) }
            }
        }
    }

    // Asked for at the first scan, never before (D15). Refused, the list carries on as it was.
    fun cameraGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    // The corner viewfinder is on only while asked for (D17): off again when
    // the trip is finished or the app leaves the screen, which releases the camera.
    var scanning by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else vm.cameraRefused()
    }
    val onScan: (() -> Unit)? = scanner?.let {
        {
            when {
                scanning -> scanning = false
                cameraGranted() -> scanning = true
                else -> cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scanning = false }
    val inTrip = ui.state?.trip != null
    var wasInTrip by remember { mutableStateOf(inTrip) }
    LaunchedEffect(inTrip) {
        if (wasInTrip && !inTrip) scanning = false
        wasInTrip = inTrip
    }

    // The phone's own touch-vibration setting has the last word: with it off, nothing is felt.
    val view = LocalView.current
    LaunchedEffect(vm) { vm.buzz.collect { view.performHapticFeedback(it.feedback()) } }

    val message = ui.message
    val undo = stringResource(R.string.undo)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message.text,
            actionLabel = if (message.undo != null) undo else null,
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
                    stringResource(R.string.history_unreadable, ui.fatal.orEmpty()),
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
                        onScan = onScan,
                        scanner = scanner?.takeIf { scanning },
                        scans = ui.scans,
                        naming = ui.naming,
                        confidence = ui.confidence,
                    )
                    Screen.STORES -> StoresScreen(state = state, snackbar = snackbar, vm = vm, onBack = { screen = Screen.LIST })
                    Screen.ROUTING -> RoutingScreen(
                        state = state,
                        lab = ui.lab,
                        snackbar = snackbar,
                        vm = vm,
                        onBack = { screen = Screen.LIST },
                    )
                    Screen.LIBRARY -> LibraryScreen(state = state, snackbar = snackbar, vm = vm, onBack = { screen = Screen.LIST })
                    Screen.SETTINGS -> SettingsScreen(vm = vm, onBack = { screen = Screen.LIST })
                    Screen.DEBUG -> DebugScreen(onBack = { screen = Screen.LIST })
                }
            }
        }
    }
}

/** A firm click for something done, as a scan gives (F12); for an untick, the lighter feel of switching off. */
private fun Buzz.feedback(): Int = when (this) {
    Buzz.ADDED, Buzz.TICKED ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
    Buzz.UNTICKED ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
}
