package dev.fajar.starter.collaboration.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.collaboration.presentation.recipient.RecipientChatThreadEvent
import dev.fajar.starter.collaboration.presentation.recipient.RecipientChatThreadViewModel
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
    private val idle = linkedMapOf<SpaceChatTarget, SpaceChatThreadStore>()
    private val active = mutableMapOf<SpaceChatThreadViewModelOwner, SpaceChatTarget>()
    internal var closed = false
        private set

    init {
        require(capacity in 1..3)
    }

    /** Prepare visible list destinations without displacing a previously opened room. */
    internal fun prepare(conversationIds: List<String>): Map<String, SpaceChatThreadStore> =
        prepareTargets(conversationIds.map { SpaceChatTarget.Conversation(it) }).mapKeys {
            (target, _) ->
            target.id
        }

    internal fun prepareRecipients(
        peerAccountIds: List<String>
    ): Map<String, SpaceChatThreadStore> =
        prepareTargets(peerAccountIds.map { SpaceChatTarget.Recipient(it) }).mapKeys { (target, _)
            ->
            target.id
        }

    internal fun prepareTargets(
        targets: List<SpaceChatTarget>
    ): Map<SpaceChatTarget, SpaceChatThreadStore> {
        check(!closed)
        val requested = targets.distinct().take(capacity)
        idle.keys.toList().forEach { id ->
            if (id !in requested && idle[id]?.wasOpened == false) idle.remove(id)?.clear()
        }
        return buildMap {
            requested.forEach { id ->
                if (id !in active.values) {
                    val stored =
                        idle[id]
                            ?: if (idle.size < capacity) {
                                SpaceChatThreadStore(id).also { idle[id] = it }
                            } else null
                    if (stored != null) put(id, stored)
                }
            }
        }
    }

    internal fun open(conversationId: String): SpaceChatThreadViewModelOwner =
        openTarget(SpaceChatTarget.Conversation(conversationId))

    internal fun openRecipient(peerAccountId: String): SpaceChatThreadViewModelOwner =
        openTarget(SpaceChatTarget.Recipient(peerAccountId))

    private fun openTarget(target: SpaceChatTarget): SpaceChatThreadViewModelOwner {
        check(!closed)
        val stored = idle.remove(target) ?: SpaceChatThreadStore(target)
        stored.wasOpened = true
        return SpaceChatThreadViewModelOwner(stored) { owner ->
                active.remove(owner)
                if (!closed) {
                    stored.releaseRoute()
                    idle.remove(target)?.clear()
                    idle[target] = stored
                    while (idle.size > capacity) {
                        val oldest =
                            idle.entries.firstOrNull { !it.value.wasOpened }?.key
                                ?: idle.keys.first()
                        idle.remove(oldest)?.clear()
                    }
                }
            }
            .also { active[it] = target }
    }

    override fun onCleared() {
        closed = true
        active.keys.forEach { it.closeGraph() }
        active.clear()
        idle.values.forEach { it.clear() }
        idle.clear()
    }
}

internal sealed interface SpaceChatTarget {
    val id: String

    data class Conversation(override val id: String) : SpaceChatTarget

    data class Recipient(override val id: String) : SpaceChatTarget
}

internal class SpaceChatThreadStore(private val target: SpaceChatTarget) {
    val models = ViewModelStore()
    var model: SpaceChatThreadViewModel? = null
        private set

    var recipientModel: RecipientChatThreadViewModel? = null
        private set

    var wasOpened = false
    var closed = false
        private set

    fun bind(model: SpaceChatThreadViewModel) {
        check(!closed)
        check(target is SpaceChatTarget.Conversation)
        check(this.model == null || this.model === model)
        this.model = model
    }

    fun bind(model: RecipientChatThreadViewModel) {
        check(!closed)
        check(target is SpaceChatTarget.Recipient)
        check(recipientModel == null || recipientModel === model)
        recipientModel = model
    }

    fun releaseRoute() {
        check(!closed)
        when (target) {
            is SpaceChatTarget.Conversation -> model?.onEvent(SpaceChatThreadEvent.RouteReleased)
            is SpaceChatTarget.Recipient ->
                recipientModel?.onEvent(RecipientChatThreadEvent.RouteReleased)
        }
    }

    fun clear() {
        if (closed) return
        closed = true
        models.clear()
        model = null
        recipientModel = null
    }
}

/** Stored in the actual navigation entry so configuration/disposal cannot leak a cache lease. */
internal class SpaceChatThreadViewModelOwner(
    private val stored: SpaceChatThreadStore,
    private val release: (SpaceChatThreadViewModelOwner) -> Unit,
) : ViewModel(), ViewModelStoreOwner {
    private var released = false

    val isOpen: Boolean
        get() = !released && !stored.closed

    override val viewModelStore: ViewModelStore
        get() {
            check(isOpen)
            return stored.models
        }

    fun bind(model: SpaceChatThreadViewModel) {
        check(isOpen)
        stored.bind(model)
    }

    fun bind(model: RecipientChatThreadViewModel) {
        check(isOpen)
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

/** Recipient model identity cannot collide with a canonical conversation or another session. */
internal fun spaceChatRecipientModelKey(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
) = "recipient:" + spaceChatThreadModelKey(access, peerAccountId)
