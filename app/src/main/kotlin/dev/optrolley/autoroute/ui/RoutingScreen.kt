package dev.optrolley.autoroute.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.core.Routers
import dev.optrolley.core.State
import dev.optrolley.core.TripScore
import java.util.Locale

/** F11: choose the routing method, and see how each would have done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    state: State,
    lab: Map<String, List<TripScore>>?,
    snackbar: SnackbarHostState,
    vm: AppViewModel,
    onBack: () -> Unit,
    routers: Routers = Routers.all,
) {
    LaunchedEffect(lab == null) { if (lab == null) vm.computeLab() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routing") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionTitle("Method") }
            item {
                Text(
                    "A change applies from the next trip, so the list holds still while you shop.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(routers.list, key = { "r:" + it.id }) { router ->
                val chosen = state.router == router.id
                ListItem(
                    headlineContent = { Text(router.label) },
                    supportingContent = { Text(router.id) },
                    leadingContent = { RadioButton(selected = chosen, onClick = null) },
                    modifier = Modifier
                        .selectable(selected = chosen, role = Role.RadioButton) { vm.chooseRouter(router.id) }
                        .testTag(Tags.router(router.id)),
                )
            }
            item { SectionTitle("Lab") }
            item {
                Text(
                    "Each ended trip, scored against what each method would have shown from the trips before it. " +
                        "Disorder is the share of item pairs it would have put the opposite way to how you walked. Lower is better.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (lab == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            } else {
                items(routers.list, key = { "l:" + it.id }) { router ->
                    LabCard(
                        label = router.label,
                        chosen = state.router == router.id,
                        scores = lab[router.id].orEmpty(),
                        storeName = { id -> state.stores[id]?.name ?: id.value },
                        modifier = Modifier.testTag(Tags.lab(router.id)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun LabCard(
    label: String,
    chosen: Boolean,
    scores: List<TripScore>,
    storeName: (dev.optrolley.core.StoreId) -> String,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (chosen) "$label (in use)" else label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(summary(scores), style = MaterialTheme.typography.bodyMedium)
            for ((store, trips) in scores.groupBy { it.store }.entries.sortedBy { storeName(it.key).lowercase() }) {
                Text(
                    "${storeName(store)}: ${summary(trips)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun summary(scores: List<TripScore>): String {
    if (scores.isEmpty()) return "No trips scored yet"
    val pairs = scores.sumOf { it.pairs }
    val disorder = scores.sumOf { it.disorder }
    val percent = if (pairs == 0) "–" else String.format(Locale.ROOT, "%.1f%%", 100.0 * disorder / pairs)
    val trips = scores.size
    return "$trips ${if (trips == 1) "trip" else "trips"} · $pairs pairs · $disorder out of order · $percent"
}
