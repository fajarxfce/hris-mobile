package dev.fajar.hris.collaboration.proof

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.util.AtomicFile
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationNotificationInputRecoveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
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
import dev.fajar.starter.sync.domain.SyncResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Real application capture/storage, fictional account, no HTTP or Matrix transport. */
internal class NotificationInputProof(private val context: Context) {
    private val directory = File(context.filesDir, "protocol-proof").apply { mkdirs() }
    private val database = createAppDatabase(context)
    private val historyKey = AndroidCredentialStore(context, "reply-proof-history-key")
    private val session = MutableStateFlow<AppResult<Session?>>(AppResult.Success(null))
    private var httpCalls = 0
    private val http =
        HttpClient(
            MockEngine {
                httpCalls++
                error("No transport in reply input proof")
            }
        )
    private val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = session

            override suspend fun current() = session.value

            override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                AppResult.Success(
                    session.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
                )

            override suspend fun clear(sessionId: String): AppResult<Boolean> =
                error("No external logout")
        }
    private val container =
        createAppContainer(
                createUserPreferences(context),
                database,
                module {},
                platform = AppPlatform.Android,
                collaborationHistoryCredentials = historyKey,
                collaborationNotificationInputCredentials =
                    AndroidCredentialStore(context, "reply-proof-input-key"),
                commandCredentials = AndroidCredentialStore(context, "reply-proof-command-key"),
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
                                IdentityServer(AUDIENCE, "Fictional reply proof")
                            }
                            single<AppBuild> { AppBuild(AppPlatform.Android, 1, "proof", null) }
                        }
                    )
                )
            }
    private val inputs
        get() = container.koin.get<CollaborationNotificationInputRepository>()

    private val intents =
        AndroidActionNotificationIntents(
            context,
            NAMESPACE,
            ComponentName(context, NotificationInputProofActivity::class.java),
            ComponentName(context, AndroidCollaborationReplyReceiver::class.java),
        )
    private val display =
        AndroidActionNotificationDisplaySource(
            context,
            NAMESPACE,
            android.R.drawable.ic_dialog_info,
            intents,
        ) {
            ActionNotificationLabels(
                "Reply proof",
                "HRIS",
                "New message",
                "Reply",
                "Mark as read",
                "Mute",
            )
        }
    val native =
        AndroidCollaborationNotificationReplies(context, intents, container.koin.get()) { outcome ->
            synchronized(this) {
                val old =
                    File(directory, "reply-callback.json")
                        .takeIf { it.isFile }
                        ?.readText()
                        ?.let(::JSONObject)
                write(
                    "reply-callback",
                    JSONObject()
                        .put("pid", Process.myPid())
                        .put("count", (old?.optInt("count") ?: 0) + 1)
                        .put("saved", outcome == CollaborationReplyCaptureOutcome.Saved),
                )
            }
        }

    suspend fun stage() {
        check(value(sessions.current()) == null)
        check(value(inputs.page()).isEmpty())
        check(historyKey.read() == null)
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        try {
            historyKey.write(Base64.encode(bytes))
        } finally {
            bytes.fill(0)
        }
        val target =
            CollaborationNotificationActionTarget(
                COMPANY,
                CONVERSATION,
                "!fictional:example.test",
                "\$fictional-original",
                NOTIFICATION,
                RENDER,
            )
        val action = CollaborationNotificationAction.Reply(OPERATION, target)
        val token =
            value(
                container.koin
                    .get<CollaborationNotificationActionRepository>()
                    .seal(originalParent(), action)
            )
        check(
            display.show(
                ActionNotificationPayload(
                    ActionNotificationReference(NOTIFICATION, RENDER),
                    "Fictional employee",
                    "Fictional input proof",
                    ActionNotificationKind.entries.associateWith { token.encoded },
                    true,
                )
            ) == ActionNotificationSubmission.Submitted
        )
        write("reply-staged", JSONObject().put("pid", Process.myPid()).put("complete", true))
    }

    suspend fun verifyRecovery() {
        check(value(sessions.current()) == null)
        val reference = value(inputs.page()).single()
        val original = requireNotNull(value(inputs.get(reference)))
        check(original.reply.text == TEXT)
        val raw = database.deviceRecords.page("hris.notification-input.v1").single()
        check(!raw.payload.contains(TEXT) && !raw.payload.contains(original.reply.token.encoded))
        check(value(container.koin.get<ReadCollaborationRecoveryWake>()()) != null)
        login(UUID.randomUUID().toString())
        check(
            container.koin.get<TransferCollaborationNotificationInput>()(reference)
                is AppResult.Failed
        )
        check(value(inputs.get(reference)) == original)
        val restored = login(ACCOUNT)
        val parent = CollaborationNativeParent(restored.id, AUDIENCE, ACCOUNT, NATIVE)
        check(
            container.koin.get<CollaborationNotificationInputRecoveryWorker>()(parent) ==
                SyncResult.Complete
        )
        check(value(inputs.page()).isEmpty())
        val access = CollaborationRecoveryAccess(restored.id, AUDIENCE, ACCOUNT)
        val copied =
            value(
                    container.koin
                        .get<CollaborationNotificationReplyRepository>()
                        .page(access, pendingOnly = false)
                )
                .inputs
                .single()
        check(
            copied.candidate.text == TEXT &&
                copied.review == CollaborationNotificationReplyReview.SessionChanged
        )
        check(
            value(
                container.koin
                    .get<CollaborationMessageRepository>()
                    .get(
                        CollaborationLocalAccess(restored.id, AUDIENCE, ACCOUNT, COMPANY),
                        OPERATION,
                    )
            ) == null
        )
        check(httpCalls == 0)
        display.clear()
        check(
            context.getSystemService(NotificationManager::class.java).activeNotifications.none {
                it.tag?.startsWith("owned:$NAMESPACE:") == true
            }
        )
        write(
            "reply-verified",
            JSONObject()
                .put("complete", true)
                .put("pid", Process.myPid())
                .put("protectedRowsBeforeRecovery", 1)
                .put("reviewRowsAfterRecovery", 1)
                .put("wrongAccountPreservedInput", true)
                .put("oldFamilyRequiresReview", true)
                .put("httpCalls", httpCalls)
                .put("commandRetained", false)
                .put("cleanedUp", true),
        )
    }

    private suspend fun login(account: String): Session {
        val now = System.currentTimeMillis()
        val next =
            Session(
                UUID.randomUUID().toString(),
                User(account, "Fictional reply owner", "reply@example.test"),
                SessionTokens("fictional-unused-access", "fictional-unused-refresh", now + 300_000),
                SessionBinding(NATIVE, AUDIENCE, now + 300_000),
            )
        session.value = AppResult.Success(next)
        database.accounts.activate(next.id, accountStorageKey(AUDIENCE, account))
        return next
    }

    suspend fun close() =
        withContext(NonCancellable) {
            try {
                container.koin.get<CollaborationNotificationReplyCallbacks>().closeAndJoin()
            } finally {
                try {
                    container.close()
                } finally {
                    http.cancelAndClose()
                }
            }
        }

    private fun write(name: String, value: JSONObject) {
        val target = AtomicFile(File(directory, "$name.json"))
        val out = target.startWrite()
        try {
            out.write(value.toString().toByteArray())
            target.finishWrite(out)
        } catch (failure: Exception) {
            target.failWrite(out)
            throw failure
        }
    }

    private fun <T> value(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }

    companion object {
        const val NAMESPACE = "reply-input-proof"
        const val TEXT = "Fictional reply retained across ordinary process death"
        private const val AUDIENCE = "https://reply.example.test"
        private const val ACCOUNT = "00000000-0000-4000-8000-000000000001"
        private const val NATIVE = "00000000-0000-4000-8000-000000000002"
        private const val COMPANY = "00000000-0000-4000-8000-000000000003"
        private const val CONVERSATION = "00000000-0000-4000-8000-000000000004"
        private const val NOTIFICATION = "00000000-0000-4000-8000-000000000005"
        private const val RENDER = "00000000-0000-4000-8000-000000000006"
        private const val OPERATION = "00000000-0000-4000-8000-000000000007"

        private fun originalParent() =
            CollaborationNativeParent("fictional-original-login", AUDIENCE, ACCOUNT, NATIVE)
    }
}
