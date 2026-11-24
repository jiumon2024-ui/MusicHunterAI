package com.musichunter.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A small on-device cache of successful public API responses.
 * This does not evade server quotas: it simply avoids unnecessary repeat requests.
 * Entries expire after 12 hours, with bounded space and total count.
 */
class SearchCache(context: Context) {
    private val directory = File(context.cacheDir, "mha_catalog_responses_v1")
    private val lifetimeMs = 12L * 60L * 60L * 1000L
    private val maxEntries = 240
    private val maxBytes = 12L * 1024L * 1024L

    private fun cacheFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$key.json")
    }

    @Synchronized fun read(url: String, now: Long = System.currentTimeMillis()): JSONObject? {
        return try {
            val file = cacheFile(url)
            if (!file.isFile) return null
            val item = JSONObject(file.readText(Charsets.UTF_8))
            val savedAt = item.optLong("savedAt", -1)
            if (savedAt <= 0 || now < savedAt || now - savedAt > lifetimeMs) {
                file.delete()
                return null
            }
            file.setLastModified(now)
            item.optJSONObject("payload")
        } catch (_: Exception) { null }
    }

    @Synchronized fun write(url: String, response: JSONObject, now: Long = System.currentTimeMillis()) {
        try {
            if (!directory.exists() && !directory.mkdirs()) return
            val payload = JSONObject().put("savedAt", now).put("payload", response).toString()
            // Do not store unexpectedly huge API responses.
            if (payload.toByteArray(Charsets.UTF_8).size > 512 * 1024) return
            val dest = cacheFile(url)
            val tmp = File(directory, "${dest.name}.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                tmp.renameTo(dest)
            }
            trim(now)
        } catch (_: Exception) { /* Cache failure must not break searching. */ }
    }

    private fun trim(now: Long) {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        val recent = files.sortedByDescending { it.lastModified() }
        var used = 0L
        recent.forEachIndexed { index, file ->
            val expired = now - file.lastModified() > lifetimeMs
            if (expired || index >= maxEntries || used + file.length() > maxBytes) file.delete()
            else used += file.length()
        }
    }
}
