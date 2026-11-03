package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*

/** Route focus is not evidence that a particular message was visible or read. */
@Composable
internal fun ChatThreadFocusObserver(
    state: ChatThreadState,
    conversationId: String,
    onFocus: (CollaborationOwner, String) -> AutoCloseable?,
) {
    val current by rememberUpdatedState(state)
    val lifecycle = LocalLifecycleOwner.current
    val window = LocalWindowInfo.current
    LaunchedEffect(lifecycle, window, conversationId, onFocus) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                    current.notificationOwner.takeIf {
                        window.isWindowFocused &&
                            !current.leaving &&
                            current.localFailure == null &&
                            current.draftDialog == null &&
                            current.cancellationDialog == null
                    }
                }
                .distinctUntilChanged()
                .collectLatest { owner ->
                    if (owner != null) {
                        val registration = onFocus(owner, conversationId)
                        if (registration != null)
                            try {
                                awaitCancellation()
                            } finally {
                                registration.close()
                            }
                    }
                }
        }
    }
}
