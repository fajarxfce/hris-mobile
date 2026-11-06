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
    private val active = mutableMapOf<ChatThreadViewModelOwner, String>()
    private var closed = false

    init {
        require(capacity in 1..3)
    }

    /** Prepare visible list destinations without displacing a previously opened room. */
    fun prepare(conversationIds: List<String>): Map<String, ThreadStore> {
        check(!closed)
        val requested = conversationIds.distinct().take(capacity)
        idle.keys.toList().forEach { id ->
            if (id !in requested && idle[id]?.wasOpened == false) idle.remove(id)?.clear()
        }
        return buildMap {
            requested.forEach { id ->
                if (id !in active.values) {
                    val stored =
                        idle[id]
                            ?: if (idle.size < capacity) {
                                ThreadStore().also { idle[id] = it }
                            } else null
                    if (stored != null) put(id, stored)
                }
            }
        }
    }

    fun open(conversationId: String): ChatThreadViewModelOwner {
        check(!closed)
        val stored = idle.remove(conversationId) ?: ThreadStore()
        stored.wasOpened = true
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
                        while (idle.size > capacity) {
                            val oldest =
                                idle.entries.firstOrNull { !it.value.wasOpened }?.key
                                    ?: idle.keys.first()
                            idle.remove(oldest)?.clear()
                        }
                    }
                }
            }
            .also { active[it] = conversationId }
    }

    override fun onCleared() {
        closed = true
        active.keys.forEach { it.closeGraph() }
        active.clear()
        idle.values.forEach { it.clear() }
        idle.clear()
    }
}

internal class ThreadStore {
    val models = ViewModelStore()
    var model: ChatThreadViewModel? = null
        private set

    var wasOpened = false
    var closed = false
        private set

    fun bind(model: ChatThreadViewModel) {
        check(!closed)
        check(this.model == null || this.model === model)
        this.model = model
    }

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
        stored.bind(model)
    }

    fun closeGraph() = stored.clear()

    override fun onCleared() {
        if (released) return
        released = true
        release(this)
    }
}
