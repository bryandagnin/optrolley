package dev.optrolley.autoroute.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.R
import dev.optrolley.autoroute.joinedBy
import dev.optrolley.core.Barcode
import dev.optrolley.core.ItemKey
import dev.optrolley.core.State

/**
 * F13: every item, with the codes that mean it, to rename one or unlink a
 * code. Used at home and rarely, so it is a plain list and asks for nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: State,
    snackbar: SnackbarHostState,
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ItemKey?>(null) }
    val items = remember(state.items) { state.items.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value }) }
    val codes = remember(state.codes) { state.codes.entries.groupBy({ it.value }, { it.key }).mapValues { (_, c) -> c.sortedBy { it.value } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.items)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag(Tags.LIBRARY),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    stringResource(if (items.isEmpty()) R.string.library_empty else R.string.library_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(16.dp)
                        .then(if (items.isEmpty()) Modifier.testTag(Tags.LIBRARY_EMPTY) else Modifier),
                )
            }
            items(items, key = { it.key.value }) { (item, name) ->
                LibraryItem(
                    item = item,
                    name = name,
                    codes = codes[item].orEmpty(),
                    onRename = { renaming = item },
                    onUnlink = vm::unlink,
                )
                HorizontalDivider()
            }
        }
    }

    // An item joined away since the dialog opened has no name left to change.
    val renamingNamed = renaming?.let { item -> state.items[item]?.let { item to it } }
    renamingNamed?.let { (item, current) ->
        RenameDialog(
            item = item,
            current = current,
            state = state,
            onDismiss = { renaming = null },
            onRename = { name ->
                vm.renameItem(item, name)
                renaming = null
            },
        )
    }
}

@Composable
private fun LibraryItem(
    item: ItemKey,
    name: String,
    codes: List<Barcode>,
    onRename: () -> Unit,
    onUnlink: (Barcode) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(Tags.libraryItem(item)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRename, modifier = Modifier.testTag(Tags.libraryRename(item))) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename_named, name))
            }
        }
        for (code in codes) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().testTag(Tags.libraryCode(code)),
            ) {
                Text(
                    code.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onUnlink(code) }, modifier = Modifier.testTag(Tags.libraryUnlink(code))) {
                    Text(stringResource(R.string.unlink))
                }
            }
        }
    }
}

/** A new name for an item, asking once if it is another item's (tier 4 §7.3). */
@Composable
private fun RenameDialog(
    item: ItemKey,
    current: String,
    state: State,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by rememberSaveable(item.value) { mutableStateOf(current) }
    var joining by remember(item) { mutableStateOf(false) }
    fun save() {
        if (name.isBlank()) return
        if (state.joinedBy(item, name) == null) onRename(name) else joining = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.tagsAsResourceIds(),
        title = { Text(stringResource(R.string.rename_item)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().testTag(Tags.RENAME_NAME),
            )
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = name.isNotBlank(), modifier = Modifier.testTag(Tags.RENAME_SAVE)) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.RENAME_CANCEL)) { Text(stringResource(R.string.cancel)) }
        },
    )
    val other = if (joining) state.joinedBy(item, name)?.let { state.items[it] } else null
    if (other != null) {
        JoinDialog(
            renamed = current,
            other = other,
            name = name.trim(),
            onDismiss = { joining = false },
            onConfirm = { onRename(name) },
        )
    }
}

/**
 * Says which two items a rename makes one, and that renaming back will not
 * part them, before it is recorded (tier 4 §7.3, O57).
 */
@Composable
internal fun JoinDialog(
    renamed: String,
    other: String,
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.tagsAsResourceIds(),
        title = { Text(stringResource(R.string.join_title)) },
        text = { Text(stringResource(R.string.join_message, renamed, other, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(Tags.JOIN_CONFIRM)) { Text(stringResource(R.string.join)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.JOIN_CANCEL)) { Text(stringResource(R.string.cancel)) }
        },
    )
}
