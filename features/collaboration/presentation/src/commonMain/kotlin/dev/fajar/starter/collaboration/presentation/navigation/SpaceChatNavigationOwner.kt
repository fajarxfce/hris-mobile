package dev.fajar.starter.collaboration.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadEvent
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadViewModel

/**
 * The account/Group graph owns three idle room stores, independently of HR selection. Live entries
 * receive independent owners, even for the same conversation. Only bounded local readers are
 * retained; these models never acquire a transport or SDK resource.
 */
class SpaceChatNavigationOwner(
    val access: CollaborationSpaceLocalAccess,
    private val capacity: Int = 3,
) : ViewModel() {
    private val idle = linkedMapOf<String, SpaceChatThreadStore>()
    private val active = mutableMapOf<SpaceChatThreadViewModelOwner, String>()
    private var closed = false

    init {
        require(capacity in 1..3)
    }

    /** Prepare visible list destinations without displacing a previously opened room. */
    internal fun prepare(conversationIds: List<String>): Map<String, SpaceChatThreadStore> {
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
                                SpaceChatThreadStore().also { idle[id] = it }
                            } else null
                    if (stored != null) put(id, stored)
                }
            }
        }
    }

    internal fun open(conversationId: String): SpaceChatThreadViewModelOwner {
        check(!closed)
        val stored = idle.remove(conversationId) ?: SpaceChatThreadStore()
        stored.wasOpened = true
        return SpaceChatThreadViewModelOwner(stored) { owner ->
                active.remove(owner)
                if (!closed) {
                    val model = stored.model
                    model?.onEvent(SpaceChatThreadEvent.RouteReleased)
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

internal class SpaceChatThreadStore {
    val models = ViewModelStore()
    var model: SpaceChatThreadViewModel? = null
        private set

    var wasOpened = false
    var closed = false
        private set

    fun bind(model: SpaceChatThreadViewModel) {
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
internal class SpaceChatThreadViewModelOwner(
    private val stored: SpaceChatThreadStore,
    private val release: (SpaceChatThreadViewModelOwner) -> Unit,
) : ViewModel(), ViewModelStoreOwner {
    private var released = false

    override val viewModelStore: ViewModelStore
        get() {
            check(!released && !stored.closed)
            return stored.models
        }

    fun bind(model: SpaceChatThreadViewModel) {
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

/** One model key shared by list preparation and the actual route's resolver. */
internal fun spaceChatThreadModelKey(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
) =
    listOf(access.audience, access.accountId, access.spaceId, access.sessionId, conversationId)
        .joinToString("") { "${it.length}:$it" }
