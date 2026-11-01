package dev.fajar.hris.collaboration.proof

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.worker.SyncWorker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Read-only assertions against the actual Android scheduler database and pending system job. */
internal class HrisScheduledWorkState(private val context: Context) {
    fun connected(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        return connectivity
            .getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    suspend fun pending(): JSONObject =
        withTimeout(15_000) {
            check(!connected())
            val manager = WorkManager.getInstance(context)
            while (true) {
                val info =
                    manager
                        .getWorkInfosForUniqueWorkFlow(QueueCollaborationText.DELIVERY_KEY)
                        .first()
                check(info.none { it.state == WorkInfo.State.RUNNING || it.state.isFinished })
                val work = info.singleOrNull()
                val scheduled =
                    work?.let { selected ->
                        pendingJobs().singleOrNull { (_, job) ->
                            job.extras.getString("EXTRA_WORK_SPEC_ID") == selected.id.toString()
                        }
                    }
                val job = scheduled?.second
                if (work != null && job != null) {
                    check(work.state == WorkInfo.State.ENQUEUED && work.runAttemptCount == 0)
                    if (android.os.Build.VERSION.SDK_INT >= 28) check(job.requiredNetwork != null)
                    else error("The owned scheduler proof requires API 28 or newer")
                    inspect(work.id)
                    return@withTimeout JSONObject()
                        .put("workId", work.id.toString())
                        .put("jobId", job.id)
                        .put("jobNamespace", scheduled.first.orEmpty())
                        .put("workState", "ENQUEUED")
                        .put("inputKeys", JSONArray(listOf(SyncWorker.TASK_KEY)))
                        .put("networkConstraint", "CONNECTED")
                        .put("workerClass", SyncWorker::class.java.name)
                        .put("runAttemptCount", 0)
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Pending work was not persisted")
        }

    private fun pendingJobs(): List<Pair<String?, JobInfo>> {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 34) {
            scheduler.pendingJobsInAllNamespaces.flatMap { (namespace, jobs) ->
                jobs.map { namespace to it }
            }
        } else scheduler.allPendingJobs.map { null to it }
    }

    private fun inspect(id: UUID) {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        check(file.isFile)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database
                .rawQuery(
                    "SELECT input, worker_class_name, state, required_network_type FROM workspec WHERE id=?",
                    arrayOf(id.toString()),
                )
                .use { row ->
                    check(row.moveToFirst())
                    check(
                        Data.fromByteArray(row.getBlob(0)).keyValueMap ==
                            mapOf(SyncWorker.TASK_KEY to QueueCollaborationText.DELIVERY_KEY)
                    )
                    check(row.getString(1) == SyncWorker::class.java.name)
                    check(row.getInt(2) == WorkInfo.State.ENQUEUED.ordinal)
                    check(row.getInt(3) == NetworkType.CONNECTED.ordinal)
                    check(!row.moveToNext())
                }
        }
    }
}
