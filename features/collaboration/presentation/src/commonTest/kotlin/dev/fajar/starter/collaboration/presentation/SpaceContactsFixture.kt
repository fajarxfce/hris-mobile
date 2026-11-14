package dev.fajar.starter.collaboration.presentation

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceDirectory
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spacecontacts.SpaceContactsViewModel
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadFixture
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

internal class SpaceContactsFixture {
    val thread = SpaceChatThreadFixture()
    val companies = mutableListOf(CollaborationSpaceContactCompany(chatId(910), "Example Company"))
    val contacts =
        mutableListOf(
            CollaborationSpaceContact(chatId(913), "Zara", companies, false),
            CollaborationSpaceContact(chatId(911), "Bima", companies, true),
            CollaborationSpaceContact(chatId(912), "Ana", companies, false),
        )
    val checkpoint =
        CollaborationSpaceDirectoryCheckpoint(
            chatId(920),
            1,
            3,
            thread.base.now,
            thread.base.now,
            null,
            true,
            true,
            false,
        )
    val facts =
        MutableStateFlow<AppResult<CollaborationSpaceDirectorySnapshot>>(
            AppResult.Success(CollaborationSpaceDirectorySnapshot(checkpoint, contacts))
        )
    var starts = 0
    var readers = 0
    var beforeRead: suspend () -> Unit = {}
    private val directory =
        object : CollaborationSpaceDirectoryRepository by thread.directory {
            override fun observe(access: CollaborationSpaceLocalAccess) = flow {
                starts++
                readers++
                try {
                    beforeRead()
                    emitAll(facts)
                } finally {
                    readers--
                }
            }
        }

    fun model() =
        SpaceContactsViewModel(
            thread.access,
            ObserveCollaborationSpaceDirectory(directory, thread.base.sessions, thread.base.server),
        )
}
