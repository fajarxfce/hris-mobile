@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.app.inspection

import androidx.compose.runtime.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.inspector.NetworkInspection
import dev.fajar.inspector.NetworkTraceSnapshot
import dev.fajar.inspector.compose.NetworkTraceFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object NetworkInspectionRoute

fun NavGraphBuilder.networkInspectionRoutes(inspection: NetworkInspection, onBack: () -> Unit) {
    composable<NetworkInspectionRoute> { NetworkInspectionScreen(inspection, onBack) }
}

@Composable
internal fun NetworkInspectionScreen(inspection: NetworkInspection, onBack: () -> Unit) {
    val recorder by inspection.recorders.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(NetworkTraceFilter()) }
    val scope = rememberCoroutineScope()
    key(recorder) {
        val snapshots = remember {
            recorder?.snapshots ?: MutableStateFlow(NetworkTraceSnapshot(closed = true))
        }
        val snapshot by snapshots.collectAsStateWithLifecycle()
        // IDs restart in each recorder. A cleared selection must never select an unrelated request.
        var selectedId by remember { mutableStateOf<Long?>(null) }
        val back = { if (selectedId != null) selectedId = null else onBack() }
        BackHandler(onBack = back)
        NetworkInspectionPage(
            snapshot = snapshot,
            selectedId = selectedId,
            filter = filter,
            onFilterChange = { filter = it },
            onTraceSelected = { selectedId = it },
            onBack = back,
            onClear = { scope.launch { inspection.reset() } },
        )
    }
}
