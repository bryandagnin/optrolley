package dev.optrolley.autoroute.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.R
import dev.optrolley.autoroute.catalogue.Known
import dev.optrolley.autoroute.catalogue.LibraryView
import dev.optrolley.autoroute.joinedBy
import dev.optrolley.core.Barcode
import dev.optrolley.core.ItemKey
import dev.optrolley.core.State
import java.io.File

/**
 * F13: every item, with the codes that mean it, to rename one or unlink a
 * code. Used at home and rarely, so it is a plain list. Each code shows what
 * the catalogue said of it; nothing else in the app reads that (O60, D14).
 * While looking codes up is off in Settings, nothing here would send
 * anything: those controls are hidden, not disabled (F14).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: State,
    snackbar: SnackbarHostState,
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val library by vm.library.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ItemKey?>(null) }
    val items = remember(state.items) { state.items.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value }) }
    val codes = remember(state.codes) { state.codes.entries.groupBy({ it.value }, { it.key }).mapValues { (_, c) -> c.sortedBy { it.value } } }
    val fetched = library.known.isNotEmpty() || library.pictures.isNotEmpty()

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
            if ((library.enabled && state.codes.isNotEmpty()) || fetched) {
                item {
                    LookUps(library = library, fetched = fetched, unasked = state.codes.keys.count { it !in library.known }, vm = vm)
                    HorizontalDivider()
                }
            }
            items(items, key = { it.key.value }) { (item, name) ->
                LibraryItem(
                    item = item,
                    name = name,
                    codes = codes[item].orEmpty(),
                    library = library,
                    onRename = { renaming = item },
                    onLookUp = vm::lookUp,
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

/**
 * Asking the catalogue about everything at once and how far it has got,
 * while the option is on; clearing what it sent, whenever it has sent
 * anything; and where that came from.
 */
@Composable
private fun LookUps(library: LibraryView, fetched: Boolean, unasked: Int, vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (library.enabled) {
            OutlinedButton(
                onClick = vm::lookUpAll,
                enabled = unasked > 0,
                modifier = Modifier.fillMaxWidth().testTag(Tags.LIBRARY_LOOK_UP_ALL),
            ) { Text(stringResource(R.string.look_up_all, unasked)) }
            library.progress?.let { (done, of) ->
                Text(
                    stringResource(R.string.looking_up, (done + 1).coerceAtMost(of), of),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp).testTag(Tags.LIBRARY_PROGRESS),
                )
            }
            if (library.stopped) {
                Text(
                    stringResource(R.string.look_ups_stopped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp).testTag(Tags.LIBRARY_STOPPED),
                )
            }
        }
        if (fetched) {
            TextButton(onClick = vm::clearFetched, modifier = Modifier.testTag(Tags.LIBRARY_CLEAR)) {
                Text(stringResource(R.string.clear_fetched))
            }
        }
        // The catalogue's licences ask that what is shown from it says where it came from.
        Text(
            stringResource(R.string.catalogue_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp).testTag(Tags.LIBRARY_SOURCE),
        )
    }
}

@Composable
private fun LibraryItem(
    item: ItemKey,
    name: String,
    codes: List<Barcode>,
    library: LibraryView,
    onRename: () -> Unit,
    onLookUp: (Barcode) -> Unit,
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
            val product = (library.known[code] as? Known.Found)?.let { label(it) }
            val picture = library.pictures[code]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().testTag(Tags.libraryCode(code)),
            ) {
                picture?.let { Picture(it, code, product ?: code.value) }
                Column(Modifier.weight(1f).padding(start = if (picture != null) 8.dp else 0.dp)) {
                    if (product != null) {
                        Text(product, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(Tags.libraryProduct(code)))
                    }
                    Text(
                        code.value,
                        style = if (product != null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (library.enabled) {
                    TextButton(onClick = { onLookUp(code) }, modifier = Modifier.testTag(Tags.libraryLookUp(code))) {
                        Text(stringResource(R.string.look_up))
                    }
                }
                TextButton(onClick = { onUnlink(code) }, modifier = Modifier.testTag(Tags.libraryUnlink(code))) {
                    Text(stringResource(R.string.unlink))
                }
            }
        }
    }
}

/** What the catalogue calls the product, to tell codes apart; never the item's name (D14). */
@Composable
private fun label(found: Known.Found): String? = when {
    found.name != null && found.brand != null -> stringResource(R.string.product_and_brand, found.name, found.brand)
    else -> found.name ?: found.brand
}

@Composable
private fun Picture(file: File, code: Barcode, of: String) {
    // The catalogue's small picture, 200 px across, so decoding it here costs little.
    val bitmap: ImageBitmap? = remember(file, file.lastModified()) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = stringResource(R.string.product_picture, of),
            modifier = Modifier.size(40.dp).testTag(Tags.libraryPicture(code)),
        )
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
