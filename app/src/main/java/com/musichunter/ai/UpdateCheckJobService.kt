package com.musichunter.ai

import android.Manifest
import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** OS-managed periodic updater. Runs with network even if the UI is not open.
 * Android decides actual execution times and may delay work for battery savings.
 * The DownloadManager owns the download; installation needs user approval.
 */
object UpdateJobs {
    private const val ID = 70617
    private const val TWELVE_HOURS = 12L * 60 * 60 * 1000

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val prefs = context.getSharedPreferences(ReleaseUpdates.PREFS, Context.MODE_PRIVATE)
        val repo = prefs.getString(ReleaseUpdates.REPO, BuildConfig.UPDATES_REPO).orEmpty()
        if (ReleaseUpdates.normalizeRepo(repo) == null) {
            scheduler.cancel(ID)
            return
        }
        if (scheduler.allPendingJobs.any { it.id == ID }) return
        val info = JobInfo.Builder(ID, ComponentName(context, UpdateCheckJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TWELVE_HOURS)
            .setPersisted(true)
            .build()
        scheduler.schedule(info)
    }
}

class UpdateCheckJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var work: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = executor.submit {
            try {
                val prefs = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
                val repo = prefs.getString(ReleaseUpdates.REPO, BuildConfig.UPDATES_REPO).orEmpty()
                if (ReleaseUpdates.normalizeRepo(repo) != null) {
                    val release = ReleaseUpdates.latest(repo)
                    prefs.edit().putLong(ReleaseUpdates.LAST_CHECK_AT, System.currentTimeMillis()).apply()
                    if (release != null && release.versionCode > BuildConfig.VERSION_CODE &&
                        prefs.getBoolean(ReleaseUpdates.AUTO_DOWNLOAD, true)) {
                        // On Android 6-9 shared Downloads requires legacy runtime permission.
                        // Without it, the foreground UI can still request the permission.
                        val canDownload = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                        if (canDownload) ReleaseUpdates.enqueueOnce(this, release)
                    }
                }
            } catch (_: Exception) {
                // Network errors and GitHub API rate limits are retried on the next OS job.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel(true)
        return true
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
