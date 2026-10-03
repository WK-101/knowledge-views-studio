package app.parley.jobs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.parley.ParleyApp
import app.parley.R
import app.parley.common.NotificationIds
import app.parley.common.catching

/**
 * Keeps Parley running while a job started from a screen runs ([UserJobs]): expedited work, which Android lets run
 * with the app in the background (no extra permission; before Android 12 it runs as WorkManager's own foreground
 * service with [getForegroundInfo]'s notification). The job itself already runs in the app's scope; this waits for it
 * to end.
 *
 * When the request runs in a later process (Parley was stopped before the job ended), the job is gone: its
 * half-written file is deleted and a notification says it didn't finish.
 */
class UserJobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobs = jobsOf(applicationContext) ?: return Result.success()
        val id = inputData.getLong(K_ID, 0)
        val token = inputData.getString(K_TOKEN).orEmpty()
        val job = jobs.job(id, token)
        when {
            job != null -> job.join()
            // Ended in this process already; nothing to do.
            token == jobs.token -> Unit
            else -> interrupted(applicationContext, id, token)
        }
        return Result.success()
    }

    private fun interrupted(context: Context, id: Long, token: String) {
        inputData.getString(K_OUTPUT)?.let { discard(context, it) }
        val kind = catching { UserJobs.Kind.valueOf(inputData.getString(K_KIND).orEmpty()) }.getOrDefault(UserJobs.Kind.EXPORT)
        // A notice from a stopped process keeps its own id apart from this process's jobs.
        val noticeId = Long.MAX_VALUE - (token.hashCode().toLong() and 0xFFFF) - id
        JobNotices.post(context, UserJobs.Finished(noticeId, kind, context.getString(R.string.job_interrupted), failed = true))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val label = inputData.getString(K_LABEL).orEmpty()
        return ForegroundInfo(NotificationIds.JOB_RUNNING, JobNotices.progress(applicationContext, UserJobs.Running(0, UserJobs.Kind.EXPORT, label)))
    }

    /** [UserJobs.Host] for the app: the work requests, and deleting partial files. */
    class AppHost(private val context: Context) : UserJobs.Host {
        override fun keepAlive(job: UserJobs.Running, output: String?, token: String) {
            val data = workDataOf(K_ID to job.id, K_TOKEN to token, K_KIND to job.kind.name, K_LABEL to job.label, K_OUTPUT to output)
            val request = OneTimeWorkRequestBuilder<UserJobWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(job.id, token), ExistingWorkPolicy.KEEP, request)
        }

        override fun ended(id: Long, token: String) {
            // Never left for a later process, which would read it as a job that didn't finish.
            WorkManager.getInstance(context).cancelUniqueWork(name(id, token))
        }

        override fun discard(output: String) = discard(context, output)
    }

    companion object {
        /** The app's job runner (tests give their own). */
        internal var jobsOf: (Context) -> UserJobs? = { (it.applicationContext as? ParleyApp)?.jobs }

        private const val TAG = "parley-user-job"
        internal const val K_ID = "id"
        internal const val K_TOKEN = "token"
        internal const val K_KIND = "kind"
        internal const val K_LABEL = "label"
        internal const val K_OUTPUT = "output"

        private fun name(id: Long, token: String) = "$TAG-$token-$id"

        /**
         * Deletes the document [output] a job was writing; when the folder's app doesn't allow deleting, empties it, so
         * no half export is ever mistaken for a whole one.
         */
        fun discard(context: Context, output: String) {
            val uri = catching { Uri.parse(output) }.getOrNull() ?: return
            val deleted = catching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            if (!deleted) catching { context.contentResolver.openOutputStream(uri, "wt")?.close() }
            Log.i("UserJobs", "Removed a partial file from a job that didn't finish (deleted=$deleted)")
        }
    }
}
