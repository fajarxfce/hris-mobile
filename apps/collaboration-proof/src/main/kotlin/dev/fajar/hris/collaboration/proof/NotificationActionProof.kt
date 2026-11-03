package dev.fajar.hris.collaboration.proof

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.AtomicFile
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.network.HttpClients
import dev.fajar.starter.network.cancelAndClose
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.securestorage.AndroidCredentialStore
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Actual callbacks/Room/Keystore/OS; fictional admitted facts. No provider or discovery actor. */
internal class NotificationActionProof(private val context: Context) {
    private val directory = File(context.filesDir, "protocol-proof").apply { mkdirs() }
    private val database = createAppDatabase(context)
    private val session =
        Session(
            "fictional-action-login",
            User(ACCOUNT, "Fictional action owner", "action@example.test"),
            SessionTokens("fictional-unused-access", "fictional-unused-refresh", Long.MAX_VALUE),
            SessionBinding(NATIVE, AUDIENCE, Long.MAX_VALUE),
        )
    private val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = flowOf(AppResult.Success(session))

            override suspend fun current() = AppResult.Success(session)

            override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                AppResult.Success(false)

            override suspend fun clear(sessionId: String) = AppResult.Success(false)
        }
    private var httpCalls = 0
    private val http =
        HttpClient(
            MockEngine {
                httpCalls++
                error("No transport in action proof")
            }
        )
    private val container =
        createAppContainer(
                createUserPreferences(context),
                database,
                module {},
                platform = AppPlatform.Android,
                collaborationListCredentials =
                    AndroidCredentialStore(context, "action-proof-list-key"),
                // Both sequential fixtures share one database and its existing protected keys.
                collaborationHistoryCredentials =
                    AndroidCredentialStore(context, "reply-proof-history-key"),
                collaborationNotificationInputCredentials =
                    AndroidCredentialStore(context, "reply-proof-input-key"),
                commandCredentials = AndroidCredentialStore(context, "action-proof-command-key"),
                workScheduler =
                    object : WorkScheduler {
                        override suspend fun enqueue(key: String) {
                            check(key == CollaborationRecoveryWake.WORK_KEY)
                        }
                    },
                http =
                    module {
                        single<HttpClient>(named(HttpClients.Authenticated)) { http }
                        single<HttpClient>(named(HttpClients.Public)) { http }
                    },
            )
            .also {
                it.koin.loadModules(
                    listOf(
                        module {
                            single<SessionRepository> { sessions }
                            single<IdentityServer> {
                                IdentityServer(AUDIENCE, "Fictional action proof")
                            }
                            single<AppBuild> { AppBuild(AppPlatform.Android, 1, "proof", null) }
                        }
                    )
                )
            }
    private val parent = CollaborationNativeParent(session.id, AUDIENCE, ACCOUNT, NATIVE)
    private val access = CollaborationLocalAccess(session.id, AUDIENCE, ACCOUNT, COMPANY)
    private val recovery = CollaborationRecoveryAccess(session.id, AUDIENCE, ACCOUNT)
    private val intents =
        AndroidActionNotificationIntents(
            context,
            NAMESPACE,
            ComponentName(context, NotificationActionProofActivity::class.java),
            ComponentName(context, AndroidCollaborationActionReceiver::class.java),
        )
    private val display =
        AndroidActionNotificationDisplaySource(
            context,
            NAMESPACE,
            android.R.drawable.ic_dialog_info,
            intents,
        ) {
            ActionNotificationLabels(
                "Action proof",
                "HRIS",
                "New message",
                "Reply",
                "Mark as read",
                "Mute",
            )
        }
    private val owner = CollaborationNotificationSurfaceOwner(MutableStateFlow(parent), display)
    private val init = Mutex()
    private var initialized = false
    private val publications
        get() = container.koin.get<CollaborationNotificationPublicationRepository>()

    private val delivery
        get() = container.koin.get<CollaborationNotificationDelivery>()

    val native =
        AndroidCollaborationNotificationActions(
            context,
            intents,
            AndroidCollaborationNotificationReplies(context, intents, container.koin.get()) {
                callback(it == CollaborationReplyCaptureOutcome.Saved, "Reply")
            },
            container.koin.get(),
        ) {
            callback(it == CollaborationActionCallbackOutcome.Applied, "Action")
        }

    private suspend fun initialize() =
        init.withLock {
            if (initialized) return@withLock
            database.accounts.activate(session.id, accountStorageKey(AUDIENCE, ACCOUNT))
            val process =
                CollaborationNotificationProcess(
                    owner,
                    read = { p, _ ->
                        AppResult.Success(
                            CollaborationNotificationPublicationReferences(p, emptyList(), null)
                        )
                    },
                    observe = { _, _, _ ->
                        error("No discovery actor in this seeded callback proof")
                    },
                    reconcile = { _, _, _ ->
                        error("No reconciliation in this seeded callback proof")
                    },
                    publishCandidate = { _, _, _ ->
                        error("No publisher in this seeded callback proof")
                    },
                    actions = container.koin.get { parametersOf(owner) },
                )
            check(delivery.start(process))
            withTimeout(8_000) {
                owner.states.first {
                    it is CollaborationNotificationSurfaceState.Ready && it.isIdle
                }
            }
            initialized = true
        }

    private suspend fun seedOriginal(mode: String): Pair<String, Long> {
        val lists = container.koin.get<CollaborationListRepository>()
        if (value(lists.checkpoint(access)) == null) {
            check(
                value(
                    lists.merge(
                        access,
                        null,
                        listOf(
                            CollaborationDirectSummary(
                                CollaborationDirectConversation(
                                    CONVERSATION,
                                    COMPANY,
                                    ACCOUNT,
                                    PEER,
                                    ACCOUNT,
                                    1,
                                    0,
                                ),
                                CollaborationParticipant(PEER, "Fictional colleague"),
                            )
                        ),
                        Clock.System.now(),
                    )
                )
            )
            check(value(lists.admit(access, null)))
        }
        val event = "\$fictional-$mode"
        val now = System.currentTimeMillis()
        check(
            value(
                container.koin
                    .get<CollaborationHistoryRepository>()
                    .merge(
                        access,
                        CONVERSATION,
                        ROOM,
                        listOf(
                            CollaborationHistoryChange.Text(
                                event,
                                "@fictional:example.test",
                                null,
                                now,
                                "Fictional original",
                                HASH,
                            )
                        ),
                    )
            )
        )
        return event to now
    }

    private suspend fun sealActions(
        target: CollaborationNotificationActionTarget,
        settings: CollaborationNotificationSettingsState,
    ): CollaborationNotificationPublicationActions {
        val actions = container.koin.get<CollaborationNotificationActionRepository>()
        val reply =
            value(
                actions.seal(
                    parent,
                    CollaborationNotificationAction.Reply(UUID.randomUUID().toString(), target),
                )
            )
        val read =
            value(
                actions.seal(
                    parent,
                    CollaborationNotificationAction.MarkRead(UUID.randomUUID().toString(), target),
                )
            )
        val mute =
            value(
                actions.seal(
                    parent,
                    CollaborationNotificationAction.Mute(
                        UUID.randomUUID().toString(),
                        target,
                        settings.version,
                        settings.settings.preview,
                    ),
                )
            )
        val dismiss =
            value(
                actions.seal(
                    parent,
                    CollaborationNotificationAction.Dismiss(UUID.randomUUID().toString(), target),
                )
            )
        return CollaborationNotificationPublicationActions(reply, read, mute, dismiss)
    }

    suspend fun stage(mode: String) {
        check(mode in MODES)
        initialize()
        val (event, now) = seedOriginal(mode)
        val target =
            CollaborationNotificationActionTarget(
                COMPANY,
                CONVERSATION,
                ROOM,
                event,
                NOTIFICATION,
                UUID.randomUUID().toString(),
            )
        val settings =
            value(
                container.koin
                    .get<CollaborationNotificationSettingsRepository>()
                    .read(access, CONVERSATION)
            )
        val tokens = sealActions(target, settings)
        val previous = value(publications.read(recovery, COMPANY, CONVERSATION))
        val body =
            CollaborationNotificationPublicationBody(
                parent,
                target,
                HASH,
                now,
                CollaborationNotificationPublicationState.Published,
                CollaborationMessageNotification(
                    access,
                    CONVERSATION,
                    ROOM,
                    event,
                    HASH,
                    settings.version,
                    null,
                    CollaborationNotificationPreview.Hidden,
                    null,
                    null,
                ),
                tokens,
            )
        check(
            value(publications.replace(recovery, previous, body))
                is CollaborationNotificationPublicationWrite.Stored
        )
        check(
            display.show(
                ActionNotificationPayload(
                    ActionNotificationReference(NOTIFICATION, target.renderId),
                    "Fictional action proof",
                    "Fictional message: $mode",
                    mapOf(
                        ActionNotificationKind.Open to tokens.reply.encoded,
                        ActionNotificationKind.Reply to tokens.reply.encoded,
                        ActionNotificationKind.MarkRead to tokens.markRead.encoded,
                        ActionNotificationKind.Mute to tokens.mute.encoded,
                        ActionNotificationKind.Dismiss to tokens.dismiss.encoded,
                    ),
                    true,
                )
            ) == ActionNotificationSubmission.Submitted
        )
        write(
            "action-staged",
            JSONObject().put("pid", Process.myPid()).put("mode", mode).put("complete", true),
        )
    }

    suspend fun open(intent: Intent) {
        initialize()
        val input = requireNotNull(intents.input(intent))
        check(input.kind == ActionNotificationKind.Open)
        val result = value(delivery.act(input)) as CollaborationNotificationActionOutcome.Navigate
        check(
            result.scope.access == access &&
                result.scope.nativeSessionId == NATIVE &&
                result.conversationId == CONVERSATION
        )
        check(
            value(publications.read(recovery, COMPANY, CONVERSATION))!!.body.state ==
                CollaborationNotificationPublicationState.Published
        )
        check(display.active().size == 1)
        callback(true, "Open")
    }

    suspend fun verify(mode: String) {
        check(mode in MODES)
        initialize()
        val body = requireNotNull(value(publications.read(recovery, COMPANY, CONVERSATION))).body
        val expected =
            when (mode) {
                "Open" -> CollaborationNotificationPublicationState.Published
                "MarkRead",
                "Mute" -> CollaborationNotificationPublicationState.Cancelled
                else -> CollaborationNotificationPublicationState.Dismissed
            }
        check(body.state == expected)
        check(display.active().size == if (mode == "Open") 1 else 0)
        val reads =
            value(
                container.koin
                    .get<CollaborationReadStateRepository>()
                    .read(access, CONVERSATION, ROOM, listOf(body.target.eventId))
            )
        check((body.target.eventId in reads.readEventIds) == (mode == "MarkRead"))
        if (mode == "Mute")
            check(
                value(
                        container.koin
                            .get<CollaborationNotificationSettingsRepository>()
                            .read(access, CONVERSATION)
                    )
                    .settings
                    .mute == CollaborationNotificationMute.Forever
            )
        if (mode == "Reply") {
            val inputs = container.koin.get<CollaborationNotificationInputRepository>()
            val saved = requireNotNull(value(inputs.get(value(inputs.page()).single())))
            check(saved.reply.text == TEXT)
            check(
                !database.deviceRecords
                    .page("hris.notification-input.v1")
                    .single()
                    .payload
                    .contains(TEXT)
            )
        }
        check(
            value(
                    container.koin
                        .get<CollaborationMessageRepository>()
                        .observe(access, null)
                        .first()
                )
                .isEmpty()
        )
        check(httpCalls == 0)
        val nativeCards = display.active().size
        if (mode == "Mute") {
            withContext(NonCancellable) {
                try {
                    container.koin.get<CollaborationNotificationActionCallbacks>().closeAndJoin()
                    container.koin.get<CollaborationNotificationReplyCallbacks>().closeAndJoin()
                    delivery.closeAndJoin()
                } finally {
                    try {
                        container.close()
                    } finally {
                        http.cancelAndClose()
                    }
                }
            }
        }
        write(
            "action-verified",
            JSONObject()
                .put("complete", true)
                .put("mode", mode)
                .put("pid", Process.myPid())
                .put("state", expected.name)
                .put("httpCalls", httpCalls)
                .put("messageCommands", 0)
                .put("nativeCards", nativeCards),
        )
    }

    private fun callback(applied: Boolean, kind: String) =
        synchronized(this) {
            val old =
                File(directory, "action-callback.json")
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.let(::JSONObject)
            write(
                "action-callback",
                JSONObject()
                    .put("count", (old?.optInt("count") ?: 0) + 1)
                    .put("applied", applied)
                    .put("kind", kind)
                    .put("pid", Process.myPid()),
            )
        }

    private fun write(name: String, value: JSONObject) {
        val file = AtomicFile(File(directory, "$name.json"))
        val out = file.startWrite()
        try {
            out.write(value.toString().toByteArray())
            file.finishWrite(out)
        } catch (failure: Exception) {
            file.failWrite(out)
            throw failure
        }
    }

    private fun <T> value(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }

    companion object {
        const val NAMESPACE = "action-callback-proof"
        const val TEXT = "Fictional native reply"
        val MODES = listOf("Open", "MarkRead", "Dismiss", "Reply", "Mute")
        private const val AUDIENCE = "https://action.example.test"
        private const val ACCOUNT = "10000000-0000-4000-8000-000000000001"
        private const val NATIVE = "10000000-0000-4000-8000-000000000002"
        private const val COMPANY = "10000000-0000-4000-8000-000000000003"
        private const val CONVERSATION = "10000000-0000-4000-8000-000000000004"
        private const val NOTIFICATION = "10000000-0000-4000-8000-000000000005"
        private const val PEER = "10000000-0000-4000-8000-000000000006"
        private const val ROOM = "!fictional:example.test"
        private val HASH = "b".repeat(64)
    }
}
