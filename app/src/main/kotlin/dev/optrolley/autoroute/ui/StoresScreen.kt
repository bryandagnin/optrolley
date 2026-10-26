package dev.optrolley.autoroute.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.ChainChoice
import dev.optrolley.autoroute.R
import dev.optrolley.core.Chain
import dev.optrolley.core.State
import dev.optrolley.core.Store

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoresScreen(state: State, snackbar: SnackbarHostState, vm: AppViewModel, onBack: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var editingStore by remember { mutableStateOf<Store?>(null) }
    var editingChain by remember { mutableStateOf<Chain?>(null) }
    val stores = state.stores.values.sortedBy { it.name.lowercase() }
    val chains = state.chains.values.sortedBy { it.name.lowercase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stores_and_chains)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_store)) },
                modifier = Modifier.testTag(Tags.ADD_STORE),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { Heading(stringResource(R.string.stores)) }
            if (stores.isEmpty()) {
                item { Hint(stringResource(R.string.no_stores_hint)) }
            }
            items(stores, key = { "s:" + it.id.value }) { store ->
                ListItem(
                    headlineContent = { Text(store.name) },
                    supportingContent = { Text(store.chain?.let { state.chains[it]?.name } ?: stringResource(R.string.no_chain)) },
                    modifier = Modifier.clickable { editingStore = store }.testTag(Tags.store(store.id)),
                )
            }
            if (chains.isNotEmpty()) {
                item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
                item { Heading(stringResource(R.string.chains)) }
                items(chains, key = { "c:" + it.id.value }) { chain ->
                    val count = state.stores.values.count { it.chain == chain.id }
                    ListItem(
                        headlineContent = { Text(chain.name) },
                        supportingContent = { Text(pluralStringResource(R.plurals.store_count, count, count)) },
                        modifier = Modifier.clickable { editingChain = chain }.testTag(Tags.chain(chain.id)),
                    )
                }
            }
        }
    }

    if (creating) {
        StoreDialog(
            title = stringResource(R.string.new_store),
            initial = null,
            state = state,
            onDismiss = { creating = false },
            onSave = { name, chain ->
                vm.createStore(name, chain)
                creating = false
            },
        )
    }
    editingStore?.let { store ->
        StoreDialog(
            title = stringResource(R.string.edit_store),
            initial = store,
            state = state,
            onDismiss = { editingStore = null },
            onSave = { name, chain ->
                vm.updateStore(store.id, name, chain)
                editingStore = null
            },
        )
    }
    editingChain?.let { chain ->
        ChainDialog(
            chain = chain,
            onDismiss = { editingChain = null },
            onSave = { name ->
                vm.renameChain(chain.id, name)
                editingChain = null
            },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A store is a name and, optionally, a chain; nothing else is asked (O39). */
@Composable
private fun StoreDialog(
    title: String,
    initial: Store?,
    state: State,
    onDismiss: () -> Unit,
    onSave: (String, ChainChoice) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var chain by remember { mutableStateOf<ChainChoice>(initial?.chain?.let { ChainChoice.Existing(it) } ?: ChainChoice.None) }
    var newChain by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val chains = state.chains.values.sortedBy { it.name.lowercase() }

    val choice = if (chain is ChainChoice.New) ChainChoice.New(newChain) else chain
    val valid = name.isNotBlank() && (choice !is ChainChoice.New || choice.name.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.tagsAsResourceIds(),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(Tags.STORE_NAME_FIELD),
                )
                Box {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().testTag(Tags.CHAIN_PICKER)) {
                        val label = when (val c = chain) {
                            ChainChoice.None -> stringResource(R.string.chain_none)
                            is ChainChoice.Existing -> stringResource(R.string.chain_named, state.chains[c.id]?.name ?: "?")
                            is ChainChoice.New -> stringResource(R.string.chain_new)
                        }
                        Text(label, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }, modifier = Modifier.tagsAsResourceIds()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.no_chain)) },
                            onClick = { chain = ChainChoice.None; picking = false },
                            modifier = Modifier.testTag(Tags.CHAIN_OPTION_NONE),
                        )
                        for (c in chains) {
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = { chain = ChainChoice.Existing(c.id); picking = false },
                                modifier = Modifier.testTag(Tags.chainOption(c.id)),
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_chain)) },
                            onClick = { chain = ChainChoice.New(""); picking = false },
                            modifier = Modifier.testTag(Tags.CHAIN_OPTION_NEW),
                        )
                    }
                }
                if (chain is ChainChoice.New) {
                    OutlinedTextField(
                        value = newChain,
                        onValueChange = { newChain = it },
                        label = { Text(stringResource(R.string.chain_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(Tags.NEW_CHAIN_FIELD),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, choice) }, enabled = valid, modifier = Modifier.testTag(Tags.STORE_SAVE)) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.STORE_CANCEL)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ChainDialog(chain: Chain, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(chain.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.tagsAsResourceIds(),
        title = { Text(stringResource(R.string.rename_chain)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(Tags.CHAIN_NAME_FIELD),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag(Tags.CHAIN_SAVE)) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.CHAIN_CANCEL)) { Text(stringResource(R.string.cancel)) }
        },
    )
}
