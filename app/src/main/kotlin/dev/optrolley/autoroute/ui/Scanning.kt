package dev.optrolley.autoroute.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.R
import dev.optrolley.autoroute.ScanEvent
import dev.optrolley.autoroute.scan.BarcodeDecoder
import dev.optrolley.autoroute.scan.Scanner
import dev.optrolley.core.Barcode
import dev.optrolley.core.ItemKey
import dev.optrolley.core.State

/** Square, as the camera is asked to crop (CameraScanner): nothing shown is left unread at the sides. */
private val MINIMAP = 120.dp

/**
 * Scanning on the list itself (F12, D17): a small viewfinder in the corner,
 * just big enough to aim with, beside the add bar and never over an entry
 * (O3). The square in its middle is where a code is read. Decoding pauses
 * while [paused], e.g. while a code is being named.
 */
@Composable
fun Minimap(scanner: Scanner, paused: Boolean, tick: Boolean, vm: AppViewModel, modifier: Modifier = Modifier) {
    val view = LocalView.current
    Box(
        modifier
            .size(MINIMAP)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .testTag(Tags.SCAN_MINIMAP),
    ) {
        scanner.Viewfinder(
            active = !paused,
            onCode = { code ->
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.LONG_PRESS,
                )
                vm.scanned(code, tick)
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Where a code has to be to be read.
        Box(
            Modifier.align(Alignment.Center)
                .size(MINIMAP * BarcodeDecoder.ZONE)
                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp)),
        )
    }
}

/** Add or tick what is scanned. Not recorded: what it leads to is (tier 4 §7.1). */
@Composable
fun ScanModeSwitch(tick: Boolean, onTick: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = !tick,
            onClick = { onTick(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.testTag(Tags.SCAN_MODE_ADD),
        ) { Text(stringResource(R.string.scan_mode_add)) }
        SegmentedButton(
            selected = tick,
            onClick = { onTick(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.testTag(Tags.SCAN_MODE_TICK),
        ) { Text(stringResource(R.string.scan_mode_tick)) }
    }
}

/** What the last scan did, with Undo while it still stands and Change to relink its code. */
@Composable
fun LastScan(event: ScanEvent, state: State, vm: AppViewModel, onChange: () -> Unit) {
    val entry = state.entries.firstOrNull { it.item == event.item }
    val undoable = when {
        event.ticked -> entry?.ticked == true
        event.added -> entry != null && !entry.ticked
        else -> false
    }
    val status = when {
        event.ticked && !undoable -> R.string.scan_undone
        event.ticked && event.added -> R.string.scan_added_and_ticked
        event.ticked -> R.string.scan_ticked
        event.added && entry == null -> R.string.scan_undone
        event.added -> R.string.scan_added
        entry?.ticked == true -> R.string.scan_already_ticked
        else -> R.string.scan_already_on_list
    }
    Row(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(Tags.scanEvent(event.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.scan_event, event.name, stringResource(status)),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (undoable) {
            TextButton(onClick = { vm.undoScan(event) }, modifier = Modifier.testTag(Tags.scanUndo(event.id))) { Text(stringResource(R.string.undo)) }
        }
        TextButton(onClick = onChange, modifier = Modifier.testTag(Tags.scanChange(event.id))) { Text(stringResource(R.string.change)) }
    }
}

/**
 * Asks what a code is. The list's own entries come first, since a code is
 * most often scanned for something already wanted; typing narrows to names
 * used before.
 */
@Composable
internal fun NamingDialog(
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
                    stringResource(R.string.naming_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
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
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.NAMING_CANCEL)) { Text(stringResource(R.string.cancel)) }
        },
    )
}
