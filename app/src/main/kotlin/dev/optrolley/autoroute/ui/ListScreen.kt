package dev.optrolley.autoroute.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.R
import dev.optrolley.autoroute.ScanEvent
import dev.optrolley.autoroute.joinedBy
import dev.optrolley.autoroute.scan.Scanner
import dev.optrolley.core.Barcode
import dev.optrolley.core.Confidence
import dev.optrolley.core.Entry
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Presentation
import dev.optrolley.core.Scope
import dev.optrolley.core.SectionKind
import dev.optrolley.core.State
import kotlinx.coroutines.delay
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
    /** Set while the corner viewfinder is on (D17). */
    scanner: Scanner? = null,
    scans: List<ScanEvent> = emptyList(),
    naming: Barcode? = null,
    confidence: Map<ItemKey, Confidence> = emptyMap(),
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Entry?>(null) }
    var changing by remember { mutableStateOf<ScanEvent?>(null) }
    // Tick while a trip is under way, add otherwise; chosen when the camera
    // comes on, not by the selected store, which outlives the trip (tier 4 §7.1).
    var tick by rememberSaveable(scanner != null) { mutableStateOf(state.trip != null) }

    // The entry a scan just added or ticked is brought into view and marked for a moment.
    val lastScan = scans.lastOrNull()
    var flash by remember { mutableStateOf<ItemKey?>(null) }

    // Lazy-list indices of headers and entries, for scrolling to one.
    val keys = remember(presentation) {
        buildList {
            for (s in presentation.sections) {
                if (s.kind != SectionKind.LIST) add(headerKey(s.kind))
                s.entries.forEach { add(entryKey(it.item)) }
            }
        }
    }

    LaunchedEffect(lastScan?.id) {
        flash = lastScan?.item ?: return@LaunchedEffect
        delay(1_500)
        flash = null
    }
    // Separate from the flash: the log can learn of a scan before the list
    // holds the entry it added, so this waits for the row to exist.
    LaunchedEffect(flash, keys) {
        val index = flash?.let { keys.indexOf(entryKey(it)) } ?: -1
        if (index >= 0) listState.animateScrollToItem(index)
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
                actions = {
                    if (state.trip != null) {
                        TextButton(onClick = vm::finishTrip, modifier = Modifier.testTag(Tags.FINISH_TRIP)) { Text(stringResource(R.string.done)) }
                    }
                    Overflow(onOpen, onExport, onImport)
                },
            )
        },
        bottomBar = {
            BottomArea(
                state = state,
                vm = vm,
                onScan = onScan,
                scanning = scanner != null,
                lastScan = lastScan,
                tick = tick,
                onTick = { tick = it },
                onChange = { changing = it },
            ) {
                scanner?.let { Minimap(it, paused = naming != null || changing != null, tick = tick, vm = vm) }
            }
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
                            stringResource(R.string.list_empty),
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
                            confidence = if (section.kind == SectionKind.RUN) confidence[entry.item] else null,
                            flash = flash == entry.item,
                        )
                    }
                }
            }
        }
    }

    if (scanner != null && naming != null) {
        NamingDialog(
            title = stringResource(R.string.new_barcode),
            initial = "",
            state = state,
            onDismiss = vm::namingDismissed,
            onSave = { vm.link(naming, it, tick) },
        )
    }
    changing?.let { event ->
        NamingDialog(
            title = stringResource(R.string.what_is_this_code),
            initial = event.name,
            state = state,
            onDismiss = { changing = null },
            onSave = {
                vm.relink(event, it)
                changing = null
            },
        )
    }

    editing?.let { entry ->
        // A name that is another item's joins the two, which renaming back
        // cannot part, so it is asked about first; cancelling returns to the edit.
        var joining by remember(entry.item) { mutableStateOf<Edit?>(null) }
        EditDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { name, quantity, note ->
                val other = state.joinedBy(entry.item, name)
                if (other == null) {
                    vm.edit(entry.item, name, quantity, note)
                    editing = null
                } else {
                    joining = Edit(name, quantity, note, state.items[other].orEmpty())
                }
            },
        )
        joining?.let { edit ->
            JoinDialog(
                renamed = entry.name,
                other = edit.other,
                name = edit.name.trim(),
                onDismiss = { joining = null },
                onConfirm = {
                    vm.edit(entry.item, edit.name, edit.quantity, edit.note)
                    joining = null
                    editing = null
                },
            )
        }
    }
}

/** An entry's edit held while a join is asked about; [other] is the name of the item it would join. */
private data class Edit(val name: String, val quantity: String, val note: String, val other: String)

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
                    stringResource(if (selected == null) R.string.not_in_a_store else R.string.shopping_at),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    selected?.name ?: stringResource(R.string.no_store),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.change_store))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.tagsAsResourceIds()) {
            if (state.trip != null) {
                Text(
                    stringResource(R.string.changing_store_finishes_trip),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.no_store), fontWeight = if (selected == null) FontWeight.Bold else null) },
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
                text = { Text(stringResource(if (state.stores.isEmpty()) R.string.add_a_store else R.string.manage_stores)) },
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
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.tagsAsResourceIds()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stores_and_chains)) },
                onClick = { open = false; onOpen(Screen.STORES) },
                modifier = Modifier.testTag(Tags.MENU_STORES),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.routing_method_and_lab)) },
                onClick = { open = false; onOpen(Screen.ROUTING) },
                modifier = Modifier.testTag(Tags.MENU_ROUTING),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.items_and_barcodes)) },
                onClick = { open = false; onOpen(Screen.LIBRARY) },
                modifier = Modifier.testTag(Tags.MENU_LIBRARY),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_history)) },
                onClick = { open = false; onExport() },
                modifier = Modifier.testTag(Tags.MENU_EXPORT),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_history)) },
                onClick = { open = false; onImport() },
                modifier = Modifier.testTag(Tags.MENU_IMPORT),
            )
            if (LocalContext.current.isDebuggable()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.debug)) },
                    onClick = { open = false; onOpen(Screen.DEBUG) },
                    modifier = Modifier.testTag(Tags.MENU_DEBUG),
                )
            }
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
                pluralStringResource(R.plurals.elsewhere_banner, count, count),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.show), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SectionHeader(kind: SectionKind, count: Int, state: State) {
    val storeName = state.selected?.let { state.stores[it]?.name } ?: stringResource(R.string.this_store)
    val (title, subtitle) = when (kind) {
        SectionKind.UNPLACED -> stringResource(R.string.section_unplaced) to stringResource(R.string.section_unplaced_hint, storeName)
        SectionKind.RUN -> stringResource(R.string.section_run) to stringResource(R.string.section_run_hint, storeName)
        SectionKind.ELSEWHERE -> stringResource(R.string.section_elsewhere, count) to stringResource(R.string.section_elsewhere_hint, storeName)
        SectionKind.LIST -> stringResource(R.string.section_list) to ""
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
private fun EntryRow(
    entry: Entry,
    section: SectionKind,
    state: State,
    vm: AppViewModel,
    onEdit: () -> Unit,
    confidence: Confidence? = null,
    flash: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val dim = if (entry.ticked) 0.55f else 1f
    val colors = MaterialTheme.colorScheme
    // The less sure the place, the warmer the row; settled rows are plain (D16).
    val resting = when {
        section == SectionKind.ELSEWHERE -> colors.surfaceContainerHighest
        confidence == Confidence.TENTATIVE -> colors.tertiaryContainer.copy(alpha = 0.45f).compositeOver(colors.surface)
        confidence == Confidence.SETTLING -> colors.tertiaryContainer.copy(alpha = 0.2f).compositeOver(colors.surface)
        else -> colors.surface
    }
    val background by animateColorAsState(if (flash) colors.primaryContainer else resting, label = "row")
    val bar = when (confidence) {
        Confidence.SETTLED -> colors.primary
        Confidence.SETTLING -> colors.tertiary
        Confidence.TENTATIVE -> colors.outline
        null -> null
    }
    val place = when (confidence) {
        Confidence.SETTLED -> stringResource(R.string.place_settled)
        Confidence.SETTLING -> stringResource(R.string.place_settling)
        Confidence.TENTATIVE -> stringResource(R.string.place_tentative)
        null -> null
    }
    val actions = stringResource(R.string.actions)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind { if (bar != null && confidence != null) drawConfidenceBar(confidence, bar) }
            .semantics { if (place != null) stateDescription = place }
            .heightIn(min = 64.dp)
            .combinedClickable(
                onClick = { vm.setTicked(entry.item, !entry.ticked) },
                onLongClick = { menu = true },
                onLongClickLabel = actions,
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
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.actions_for, entry.name))
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
            text = { Text(stringResource(R.string.edit)) },
            onClick = { onDismiss(); onEdit() },
            modifier = Modifier.testTag(Tags.actionEdit(entry.item)),
        )
        if (section == SectionKind.ELSEWHERE) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.available_after_all)) },
                onClick = { onDismiss(); vm.availableAfterAll(entry.item) },
                modifier = Modifier.testTag(Tags.actionAvailable(entry.item)),
            )
        } else if (store == null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.not_here_choose_store)) },
                onClick = {},
                enabled = false,
                modifier = Modifier.testTag(Tags.actionNotHereStore(entry.item)),
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.not_at_store, store.name)) },
                onClick = { onDismiss(); vm.notHere(entry.item, Scope.STORE) },
                modifier = Modifier.testTag(Tags.actionNotHereStore(entry.item)),
            )
            if (chain != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.not_at_chain, chain.name)) },
                    onClick = { onDismiss(); vm.notHere(entry.item, Scope.CHAIN) },
                    modifier = Modifier.testTag(Tags.actionNotHereChain(entry.item)),
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); vm.remove(entry.item) },
            modifier = Modifier.testTag(Tags.actionRemove(entry.item)),
        )
    }
}

@Composable
private fun BottomArea(
    state: State,
    vm: AppViewModel,
    onScan: (() -> Unit)?,
    scanning: Boolean,
    lastScan: ScanEvent?,
    tick: Boolean,
    onTick: (Boolean) -> Unit,
    onChange: (ScanEvent) -> Unit,
    camera: @Composable () -> Unit,
) {
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
        Row(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(8.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if (scanning) {
                    lastScan?.let { LastScan(it, state, vm, onChange = { onChange(it) }) }
                    ScanModeSwitch(tick, onTick, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
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
                            label = { Text(stringResource(R.string.quantity)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag(Tags.ADD_QUANTITY),
                        )
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text(stringResource(R.string.note)) },
                            singleLine = true,
                            modifier = Modifier.weight(2f).testTag(Tags.ADD_NOTE),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconToggleButton(checked = details, onCheckedChange = { details = it }, modifier = Modifier.testTag(Tags.ADD_DETAILS)) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.quantity_and_note))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(R.string.add_an_item)) },
                        trailingIcon = onScan?.let {
                            {
                                IconToggleButton(checked = scanning, onCheckedChange = { _ -> it() }, modifier = Modifier.testTag(Tags.SCAN_BUTTON)) {
                                    Icon(
                                        Icons.Default.QrCodeScanner,
                                        contentDescription = stringResource(if (scanning) R.string.stop_scanning else R.string.scan_barcodes),
                                    )
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
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
                    }
                }
            }
            if (scanning) {
                Spacer(Modifier.width(8.dp))
                camera()
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
        title = { Text(stringResource(R.string.edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.testTag(Tags.EDIT_NAME),
                )
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text(stringResource(R.string.quantity)) },
                    singleLine = true,
                    modifier = Modifier.testTag(Tags.EDIT_QUANTITY),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.note)) },
                    modifier = Modifier.testTag(Tags.EDIT_NOTE),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, quantity, note) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag(Tags.EDIT_SAVE),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.EDIT_CANCEL)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * The confidence bar at a Run row's start edge. Its shape carries the level
 * as well as its colour: whole for settled, half for settling, dotted for
 * tentative.
 */
private fun DrawScope.drawConfidenceBar(confidence: Confidence, color: Color) {
    val width = 4.dp.toPx()
    when (confidence) {
        Confidence.SETTLED -> drawRect(color, size = Size(width, size.height))
        Confidence.SETTLING -> drawRect(color, topLeft = Offset(0f, size.height / 4), size = Size(width, size.height / 2))
        Confidence.TENTATIVE -> {
            val step = width * 3
            var y = step / 2
            while (y < size.height) {
                drawCircle(color, radius = width / 2, center = Offset(width / 2, y))
                y += step
            }
        }
    }
}
