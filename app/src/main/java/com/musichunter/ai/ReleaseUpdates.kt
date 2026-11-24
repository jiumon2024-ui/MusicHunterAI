package com.musichunter.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from a public GitHub Releases repository, without API keys or a private server.
 * APK installation is always completed by the Android system with user consent.
 * No arbitrary URLs are accepted for the APK: only public github.com release assets.
 */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val digest: String?
)

object ReleaseUpdates {
    const val PREFS = "musichunter_updates"
    const val REPO = "repository"
    const val AUTO_DOWNLOAD = "auto_download"
    const val DOWNLOAD_ID = "download_id"
    const val PROMPTED_DOWNLOAD_ID = "prompted_download_id"
    const val DOWNLOAD_VERSION = "download_version"
    const val LAST_CHECK_AT = "last_check_at"
    const val LAST_CHECK_VERSION = "last_check_version"
    private val repoRegex = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private val tagRegex = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

    fun normalizeRepo(raw: String): String? {
        val cleaned = raw.trim().removeSuffix("/")
        val repo = when {
            cleaned.startsWith("https://github.com/") -> cleaned.removePrefix("https://github.com/")
            else -> cleaned
        }.removeSuffix(".git")
        if (!repoRegex.matches(repo) || repo.contains("..")) return null
        return repo
    }

    fun versionCodeForTag(tag: String): Int? {
        val match = tagRegex.matchEntire(tag.trim()) ?: return null
        val (major, minor, patch) = match.destructured
        val a = major.toLongOrNull() ?: return null
        val b = minor.toLongOrNull() ?: return null
        val c = patch.toLongOrNull() ?: return null
        if (b > 99 || c > 99) return null
        if (a > 214_000) return null
        val code = a * 10_000L + b * 100L + c
        return if (code >= 1L && code <= Int.MAX_VALUE.toLong()) code.toInt() else null
    }

    fun latest(repo: String): AppRelease? {
        val normalized = normalizeRepo(repo) ?: throw IllegalArgumentException("Укажи ссылку вида username/repository")
        val connection = (URL("https://api.github.com/repos/$normalized/releases/latest").openConnection() as HttpURLConnection)
        connection.apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MusicHunterAI-Android-Updater")
        }
        try {
            val response = connection.responseCode
            if (response == 404) return null
            if (response == 403 || response == 429) throw IllegalStateException("GitHub временно ограничил проверки обновлений")
            if (response !in 200..299) throw IllegalStateException("GitHub: ответ HTTP $response")
            val json = connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.length > 750_000) throw IllegalStateException("Ответ сервера слишком большой")
                JSONObject(text)
            }
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            val code = versionCodeForTag(tag) ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                if (!item.optString("name").equals("MusicHunterAI.apk", ignoreCase = true)) continue
                val url = item.optString("browser_download_url")
                if (!url.startsWith("https://github.com/$normalized/releases/download/")) continue
                val digest = item.optString("digest").takeIf { it.startsWith("sha256:") && it.length == 71 }
                return AppRelease(code, tag.removePrefix("v"), json.optString("body").take(800), url, digest)
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, release: AppRelease): Long {
        require(release.downloadUrl.startsWith("https://github.com/"))
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Music Hunter AI ${release.versionName}")
            .setDescription("Новая версия. После загрузки подтвердите установку в Android.")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MusicHunterAI-${release.versionName}.apk")
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    /** Duplicate prevention shared by foreground and background checks. */
    @Synchronized
    fun enqueueOnce(context: Context, release: AppRelease): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastId = prefs.getLong(DOWNLOAD_ID, -1L)
        if (prefs.getInt(DOWNLOAD_VERSION, -1) == release.versionCode) {
            val state = getDownloadState(context, lastId)
            if (state == DownloadManager.STATUS_PENDING || state == DownloadManager.STATUS_RUNNING ||
                state == DownloadManager.STATUS_PAUSED || state == DownloadManager.STATUS_SUCCESSFUL) return lastId
        }
        val id = download(context, release)
        prefs.edit().putLong(DOWNLOAD_ID, id)
            .putInt(DOWNLOAD_VERSION, release.versionCode)
            .putLong(PROMPTED_DOWNLOAD_ID, -1L).apply()
        return id
    }

    fun downloadStateFor(context: Context, version: Int): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(DOWNLOAD_VERSION, -1) != version) return null
        return getDownloadState(context, prefs.getLong(DOWNLOAD_ID, -1L))
    }

    fun getDownloadState(context: Context, id: Long): Int? {
        if (id < 0) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val column = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return if (column >= 0) it.getInt(column) else null
        }
    }
}
