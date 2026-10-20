package dev.optrolley.autoroute.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.core.Entry
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Presentation
import dev.optrolley.core.Scope
import dev.optrolley.core.SectionKind
import dev.optrolley.core.State
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    state: State,
    presentation: Presentation,
    focus: ItemKey?,
    snackbar: SnackbarHostState,
    vm: AppViewModel,
    onOpen: (Screen) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onScan: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Entry?>(null) }

    // Lazy-list indices of headers and entries, for scrolling to one.
    val keys = remember(presentation) {
        buildList {
            for (s in presentation.sections) {
                if (s.kind != SectionKind.LIST) add(headerKey(s.kind))
                s.entries.forEach { add(entryKey(it.item)) }
            }
        }
    }

    LaunchedEffect(focus, keys) {
        if (focus == null) return@LaunchedEffect
        val index = keys.indexOf(entryKey(focus))
        if (index >= 0) {
            listState.animateScrollToItem(index)
            vm.focusShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { StorePicker(state, onSelect = vm::select, onManage = { onOpen(Screen.STORES) }) },
                actions = { Overflow(onOpen, onExport, onImport) },
            )
        },
        bottomBar = {
            BottomArea(state = state, vm = vm, onScan = onScan)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val elsewhere = presentation.elsewhereCount
            if (elsewhere > 0) {
                ElsewhereBanner(elsewhere) {
                    val index = keys.indexOf(headerKey(SectionKind.ELSEWHERE))
                    if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(Tags.LIST),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (presentation.sections.all { it.entries.isEmpty() }) {
                    item("empty") {
                        Text(
                            "Nothing on the list. Add something below.",
                            modifier = Modifier.fillMaxWidth().padding(32.dp).testTag(Tags.EMPTY_LIST),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                for (section in presentation.sections) {
                    if (section.kind != SectionKind.LIST) {
                        item(headerKey(section.kind)) {
                            SectionHeader(section.kind, section.entries.size, state)
                        }
                    }
                    items(section.entries, key = { entryKey(it.item) }) { entry ->
                        EntryRow(
                            entry = entry,
                            section = section.kind,
                            state = state,
                            vm = vm,
                            onEdit = { editing = entry },
                        )
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        EditDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { name, quantity, note ->
                vm.edit(entry.item, name, quantity, note)
                editing = null
            },
        )
    }
}

private fun headerKey(kind: SectionKind) = "h:${kind.name}"

private fun entryKey(item: ItemKey) = "e:${item.value}"

/** The selected store, always on screen (O40), and the way to change it. */
@Composable
private fun StorePicker(state: State, onSelect: (dev.optrolley.core.StoreId?) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = state.selected?.let { state.stores[it] }
    Box {
        Row(
            modifier = Modifier
                .clickable { open = true }
                .testTag(Tags.STORE_PICKER)
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Storefront, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    if (selected == null) "Not in a store" else "Shopping at",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    selected?.name ?: "No store",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Change store")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.tagsAsResourceIds()) {
            if (state.trip != null) {
                Text(
                    "Changing store finishes this trip",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenuItem(
                text = { Text("No store", fontWeight = if (selected == null) FontWeight.Bold else null) },
                onClick = { open = false; onSelect(null) },
                modifier = Modifier.testTag(Tags.STORE_OPTION_NONE),
            )
            for (store in state.stores.values.sortedBy { it.name.lowercase() }) {
                val chain = store.chain?.let { state.chains[it]?.name }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(store.name, fontWeight = if (store.id == state.selected) FontWeight.Bold else null)
                            if (chain != null) Text(chain, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    onClick = { open = false; onSelect(store.id) },
                    modifier = Modifier.testTag(Tags.storeOption(store.id)),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (state.stores.isEmpty()) "Add a store…" else "Manage stores…") },
                onClick = { open = false; onManage() },
                modifier = Modifier.testTag(Tags.MANAGE_STORES),
            )
        }
    }
}

@Composable
private fun Overflow(onOpen: (Screen) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(Tags.OVERFLOW)) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.tagsAsResourceIds()) {
            DropdownMenuItem(
                text = { Text("Stores and chains") },
                onClick = { open = false; onOpen(Screen.STORES) },
                modifier = Modifier.testTag(Tags.MENU_STORES),
            )
            DropdownMenuItem(
                text = { Text("Routing method and lab") },
                onClick = { open = false; onOpen(Screen.ROUTING) },
                modifier = Modifier.testTag(Tags.MENU_ROUTING),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Export history…") },
                onClick = { open = false; onExport() },
                modifier = Modifier.testTag(Tags.MENU_EXPORT),
            )
            DropdownMenuItem(
                text = { Text("Import history…") },
                onClick = { open = false; onImport() },
                modifier = Modifier.testTag(Tags.MENU_IMPORT),
            )
        }
    }
}

/** O4: the Elsewhere count stays on screen however far the list is scrolled. */
@Composable
private fun ElsewhereBanner(count: Int, onShow: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onShow).testTag(Tags.ELSEWHERE_BANNER),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "$count ${if (count == 1) "item" else "items"} to get elsewhere",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text("Show", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SectionHeader(kind: SectionKind, count: Int, state: State) {
    val storeName = state.selected?.let { state.stores[it]?.name } ?: "this store"
    val (title, subtitle) = when (kind) {
        SectionKind.UNPLACED -> "Unplaced" to "No known place at $storeName yet"
        SectionKind.RUN -> "Run" to "In the order you walk $storeName"
        SectionKind.ELSEWHERE -> "Elsewhere ($count)" to "Said not to be at $storeName"
        SectionKind.LIST -> "List" to ""
    }
    val container = if (kind == SectionKind.ELSEWHERE) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Column(
        Modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { heading() }
            .testTag(Tags.section(kind)),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One entry. The whole row toggles the tick, so it can be hit without
 * looking; a tick only marks the entry, it never moves it (O28).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: Entry, section: SectionKind, state: State, vm: AppViewModel, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val dim = if (entry.ticked) 0.55f else 1f
    val background = if (section == SectionKind.ELSEWHERE) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .heightIn(min = 64.dp)
            .combinedClickable(
                onClick = { vm.setTicked(entry.item, !entry.ticked) },
                onLongClick = { menu = true },
                onLongClickLabel = "Actions",
            )
            .testTag(Tags.entry(entry.item))
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = entry.ticked,
            onCheckedChange = { vm.setTicked(entry.item, it) },
            modifier = Modifier
                .size(56.dp)
                .semantics { contentDescription = entry.name }
                .testTag(Tags.tick(entry.item)),
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (entry.ticked) TextDecoration.LineThrough else null,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
            )
            entry.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                    textDecoration = if (entry.ticked) TextDecoration.LineThrough else null,
                )
            }
        }
        entry.quantity?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary.copy(alpha = dim),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(Tags.entryMenu(entry.item))) {
                Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${entry.name}")
            }
            EntryMenu(
                expanded = menu,
                onDismiss = { menu = false },
                entry = entry,
                section = section,
                state = state,
                vm = vm,
                onEdit = onEdit,
            )
        }
    }
}

@Composable
private fun EntryMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entry: Entry,
    section: SectionKind,
    state: State,
    vm: AppViewModel,
    onEdit: () -> Unit,
) {
    val store = state.selected?.let { state.stores[it] }
    val chain = store?.chain?.let { state.chains[it] }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.tagsAsResourceIds()) {
        DropdownMenuItem(
            text = { Text("Edit") },
            onClick = { onDismiss(); onEdit() },
            modifier = Modifier.testTag(Tags.actionEdit(entry.item)),
        )
        if (section == SectionKind.ELSEWHERE) {
            DropdownMenuItem(
                text = { Text("Available here after all") },
                onClick = { onDismiss(); vm.availableAfterAll(entry.item) },
                modifier = Modifier.testTag(Tags.actionAvailable(entry.item)),
            )
        } else if (store == null) {
            DropdownMenuItem(
                text = { Text("Not here (choose a store first)") },
                onClick = {},
                enabled = false,
                modifier = Modifier.testTag(Tags.actionNotHereStore(entry.item)),
            )
        } else {
            DropdownMenuItem(
                text = { Text("Not at ${store.name}") },
                onClick = { onDismiss(); vm.notHere(entry.item, Scope.STORE) },
                modifier = Modifier.testTag(Tags.actionNotHereStore(entry.item)),
            )
            if (chain != null) {
                DropdownMenuItem(
                    text = { Text("Not at any ${chain.name}") },
                    onClick = { onDismiss(); vm.notHere(entry.item, Scope.CHAIN) },
                    modifier = Modifier.testTag(Tags.actionNotHereChain(entry.item)),
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); vm.remove(entry.item) },
            modifier = Modifier.testTag(Tags.actionRemove(entry.item)),
        )
    }
}

@Composable
private fun BottomArea(state: State, vm: AppViewModel, onScan: (() -> Unit)?) {
    var text by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }

    fun submit(name: String = text) {
        if (name.isBlank()) return
        vm.add(name, quantity, note)
        text = ""
        quantity = ""
        note = ""
    }

    val typed = text.trim().lowercase()
    val onList = remember(state.entries) { state.entries.map { it.item }.toSet() }
    val suggestions = remember(typed, state.known, onList) {
        if (typed.isEmpty()) {
            emptyList()
        } else {
            state.known.entries
                .filter { (key, name) -> key !in onList && name.lowercase().startsWith(typed) }
                .sortedBy { it.value.lowercase() }
                .take(10)
        }
    }

    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(8.dp)) {
            if (state.trip != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Trip in progress",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    FilledTonalButton(onClick = vm::finishTrip, modifier = Modifier.testTag(Tags.FINISH_TRIP)) {
                        Text("Finish trip")
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    items(suggestions, key = { it.key.value }) { (key, name) ->
                        SuggestionChip(
                            onClick = { submit(name) },
                            label = { Text(name) },
                            modifier = Modifier.testTag(Tags.suggestion(key)),
                        )
                    }
                }
            }
            if (details) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Quantity") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag(Tags.ADD_QUANTITY),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note") },
                        singleLine = true,
                        modifier = Modifier.weight(2f).testTag(Tags.ADD_NOTE),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconToggleButton(checked = details, onCheckedChange = { details = it }, modifier = Modifier.testTag(Tags.ADD_DETAILS)) {
                    Icon(Icons.Default.Tune, contentDescription = "Quantity and note")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Add an item") },
                    trailingIcon = onScan?.let {
                        {
                            IconButton(onClick = it, modifier = Modifier.testTag(Tags.SCAN_BUTTON)) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan barcodes")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f).testTag(Tags.ADD_FIELD),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { submit() }, enabled = text.isNotBlank(), modifier = Modifier.testTag(Tags.ADD_BUTTON)) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
        }
    }
}

@Composable
private fun EditDialog(entry: Entry, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable(entry.item.value) { mutableStateOf(entry.name) }
    var quantity by rememberSaveable(entry.item.value) { mutableStateOf(entry.quantity.orEmpty()) }
    var note by rememberSaveable(entry.item.value) { mutableStateOf(entry.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.tagsAsResourceIds(),
        title = { Text("Edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.testTag(Tags.EDIT_NAME),
                )
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text("Quantity") },
                    singleLine = true,
                    modifier = Modifier.testTag(Tags.EDIT_QUANTITY),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.testTag(Tags.EDIT_NOTE),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, quantity, note) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag(Tags.EDIT_SAVE),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.EDIT_CANCEL)) { Text("Cancel") }
        },
    )
}
