package dev.optrolley.autoroute.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.ScanEvent
import dev.optrolley.autoroute.scan.Scanner
import dev.optrolley.core.Barcode
import dev.optrolley.core.ItemKey
import dev.optrolley.core.State

/**
 * Scan code after code to add them or, in the shop, to tick them (F12).
 * Decoding pauses while a code is being named.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    state: State,
    scans: List<ScanEvent>,
    naming: Barcode?,
    scanner: Scanner,
    snackbar: SnackbarHostState,
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    var changing by remember { mutableStateOf<ScanEvent?>(null) }
    // Tick while a trip is under way, add otherwise; not by the selected
    // store, which stays selected after the trip and must not tick empties.
    var tick by rememberSaveable { mutableStateOf(state.trip != null) }
    // Newest first, and kept in view: a list holds its first visible row still
    // when one is inserted above it, which would hide each new scan.
    val log = rememberLazyListState()
    LaunchedEffect(scans.size) { if (scans.isNotEmpty()) log.scrollToItem(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tick) "Scan to tick" else "Scan to add") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the list")
                    }
                },
                actions = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag(Tags.SCAN_DONE)) { Text("Done") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().testTag(Tags.SCAN_SCREEN)) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black)) {
                scanner.Viewfinder(
                    active = naming == null && changing == null,
                    onCode = { code ->
                        view.performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                            else HapticFeedbackConstants.LONG_PRESS,
                        )
                        vm.scanned(code, tick)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = !tick,
                    onClick = { tick = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag(Tags.SCAN_MODE_ADD),
                ) { Text("Add") }
                SegmentedButton(
                    selected = tick,
                    onClick = { tick = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag(Tags.SCAN_MODE_TICK),
                ) { Text("Tick") }
            }
            if (scans.isEmpty()) {
                Text(
                    if (tick) "Scan things as you pick them up. Each one is ticked; anything not on the list is added and ticked."
                    else "Point the camera at a barcode. Codes you have named before are added straight away.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = log, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(scans.asReversed(), key = { it.id }) { event ->
                    ScanRow(event, state, vm, onChange = { changing = event })
                    HorizontalDivider()
                }
            }
        }
    }

    if (naming != null) {
        NamingDialog(
            title = "New barcode",
            initial = "",
            state = state,
            onDismiss = vm::namingDismissed,
            onSave = { vm.link(naming, it, tick) },
        )
    }
    changing?.let { event ->
        NamingDialog(
            title = "What is this code?",
            initial = event.name,
            state = state,
            onDismiss = { changing = null },
            onSave = {
                vm.relink(event, it)
                changing = null
            },
        )
    }
}

@Composable
private fun ScanRow(event: ScanEvent, state: State, vm: AppViewModel, onChange: () -> Unit) {
    val entry = state.entries.firstOrNull { it.item == event.item }
    // Undo only what this scan did and is still there to take back.
    val undoable = when {
        event.ticked -> entry?.ticked == true
        event.added -> entry != null && !entry.ticked
        else -> false
    }
    val status = when {
        event.ticked && !undoable -> "Undone"
        event.ticked && event.added -> "Not on the list: added and ticked"
        event.ticked -> "Ticked"
        event.added && entry == null -> "Undone"
        event.added -> "Added"
        entry?.ticked == true -> "Already ticked"
        else -> "Already on the list"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(Tags.scanEvent(event.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(event.name, style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onChange, modifier = Modifier.testTag(Tags.scanChange(event.id))) { Text("Change") }
        if (undoable) {
            TextButton(onClick = { vm.undoScan(event) }, modifier = Modifier.testTag(Tags.scanUndo(event.id))) { Text("Undo") }
        }
    }
}

/**
 * Asks what a code is. The list's own entries come first, since a code is
 * most often scanned for something already wanted; typing narrows to names
 * used before.
 */
@Composable
private fun NamingDialog(
    title: String,
    initial: String,
    state: State,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(title) { mutableStateOf(initial) }
    val typed = name.trim().lowercase()
    val suggestions = remember(typed, state.entries, state.known) {
        val onList = state.entries.filter { !it.ticked }.map { it.item to it.name }
        val known = state.known.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
        (onList + known)
            .distinctBy { it.first }
            .filter { typed.isEmpty() || it.second.lowercase().startsWith(typed) }
            .filter { it.first != ItemKey.of(name) }
            .take(if (typed.isEmpty()) onList.size.coerceAtMost(12) else 10)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.tagsAsResourceIds(),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Name it as you would write it on the list. Brands that will do instead of each other can share one name.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name) }),
                    modifier = Modifier.fillMaxWidth().testTag(Tags.NAMING_FIELD),
                )
                if (suggestions.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions, key = { it.first.value }) { (key, suggested) ->
                            SuggestionChip(
                                onClick = { onSave(suggested) },
                                label = { Text(suggested, fontWeight = FontWeight.Medium) },
                                modifier = Modifier.testTag(Tags.namingSuggestion(key)),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag(Tags.NAMING_SAVE),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.NAMING_CANCEL)) { Text("Cancel") }
        },
    )
}
