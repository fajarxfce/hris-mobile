package dev.fajar.starter.collaboration.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadEvent
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadViewModel

/**
 * The account/company navigation graph owns three idle room stores. Live back-stack entries always
 * receive independent owners, even for the same conversation. No SDK resource is pinned by this
 * cache; the room's normal Stopped event releases foreground work.
 */
internal class ChatThreadViewModelStores(private val capacity: Int = 3) : ViewModel() {
    private val idle = linkedMapOf<String, ThreadStore>()
    private val active = mutableSetOf<ChatThreadViewModelOwner>()
    private var closed = false

    init {
        require(capacity in 1..3)
    }

    fun open(conversationId: String): ChatThreadViewModelOwner {
        check(!closed)
        val stored = idle.remove(conversationId) ?: ThreadStore()
        return ChatThreadViewModelOwner(stored) { owner ->
                active.remove(owner)
                if (!closed) {
                    val model = stored.model
                    if (model != null && !model.canRetainAfterNavigation) {
                        stored.clear()
                    } else {
                        model?.onEvent(ChatThreadEvent.RouteReleased)
                        idle.remove(conversationId)?.clear()
                        idle[conversationId] = stored
                        while (idle.size > capacity) idle.remove(idle.keys.first())?.clear()
                    }
                }
            }
            .also(active::add)
    }

    override fun onCleared() {
        closed = true
        active.forEach { it.closeGraph() }
        active.clear()
        idle.values.forEach { it.clear() }
        idle.clear()
    }
}

internal class ThreadStore {
    val models = ViewModelStore()
    var model: ChatThreadViewModel? = null
    var closed = false
        private set

    fun clear() {
        if (closed) return
        closed = true
        models.clear()
        model = null
    }
}

/** Stored in the actual navigation entry so configuration/disposal cannot leak a cache lease. */
internal class ChatThreadViewModelOwner(
    private val stored: ThreadStore,
    private val release: (ChatThreadViewModelOwner) -> Unit,
) : ViewModel(), ViewModelStoreOwner {
    private var released = false

    override val viewModelStore: ViewModelStore
        get() {
            check(!released && !stored.closed)
            return stored.models
        }

    fun bind(model: ChatThreadViewModel) {
        check(!released && !stored.closed)
        check(stored.model == null || stored.model === model)
        stored.model = model
    }

    fun closeGraph() = stored.clear()

    override fun onCleared() {
        if (released) return
        released = true
        release(this)
    }
}
