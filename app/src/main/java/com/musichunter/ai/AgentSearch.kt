package com.musichunter.ai

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A playable audio item. Downloads must be permissioned; no DRM or paywall bypass. */
data class Song(
    val title: String,
    val artist: String,
    val audioUrl: String,
    val pageUrl: String,
    val provider: String,
    val license: String,
    val licenseUrl: String = "",
    val verifiedDownload: Boolean = false,
    val format: String = "audio",
    val durationMs: Long = 0L
)

/** Free rule-based search planner. Does not pretend to be an LLM. */
object AgentPlanner {
    fun variants(input: String): List<String> {
        val source = input.trim().replace(Regex("\\s+"), " ")
        val base = source.replace(
            Regex("(?i)\\b(найди|поищи|скачай|подбери|песни|треки|музыку|можно|мне|пожалуйста|хочу)\\b"),
            " "
        ).replace(Regex("\\s+"), " ").trim().ifEmpty { source }
        val queries = linkedSetOf(base)
        if (Regex("(?i)ремикс|remix").containsMatchIn(source)) {
            queries += base.replace(Regex("(?i)ремикс"), "remix")
        } else if (Regex("(?i)минус|инструментал|instrumental").containsMatchIn(source)) {
            queries += base.replace(Regex("(?i)инструментал|минус"), "instrumental")
        } else if (base.split(" ").size <= 4) {
            queries += "$base music"
        }
        return queries.take(2)
    }
}

/** No API keys and no servers. All services impose their own network limits. */
class AgentSearch(private val monitor: SourceMonitor, private val cache: SearchCache) {
    private var cacheHits = 0
    private var networkRequests = 0
    private var networkAllowed = true

    private class CacheMissException : Exception("Нет сохранённого ответа")
    private fun encoded(s: String): String = Uri.encode(s)

    private fun getJson(url: String): JSONObject {
        require(url.startsWith("https://")) { "Only HTTPS API endpoints are accepted" }
        cache.read(url)?.let {
            cacheHits++
            return it
        }
        if (!networkAllowed) throw CacheMissException()
        networkRequests++
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MusicHunterAI/0.1 (Android; contact via app)")
            instanceFollowRedirects = true
        }
        try {
            val status = connection.responseCode
            if (status == 429 || (status == 503 && connection.getHeaderField("Retry-After") != null)) {
                throw RateLimitException(connection.getHeaderField("Retry-After"), status)
            }
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val apiError = response.optJSONObject("error")
            if (apiError?.optString("code") == "ratelimited") throw RateLimitException(null, 429)
            cache.write(url, response)
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun search(input: String, only: Set<MusicSource> = MusicSource.values().toSet(),
               progress: (String) -> Unit): List<Song> {
        cacheHits = 0
        networkRequests = 0
        val tracks = mutableListOf<Song>()
        val variants = AgentPlanner.variants(input)
        val q = variants.firstOrNull() ?: return emptyList()
        for (source in MusicSource.values()) {
            if (source !in only) continue
            networkAllowed = monitor.canRequest(source)
            if (networkAllowed) monitor.checking(source)
            progress(if (networkAllowed) "${source.label}: ищу доступные записи…"
                     else "${source.label}: временный лимит, пробую сохранённые результаты…")
            try {
                val before = tracks.size
                val networkBefore = networkRequests
                when (source) {
                    MusicSource.OPENVERSE -> {
                        val primary = openverse(q)
                        tracks += primary
                        // Spend a second API request only if the primary search is sparse.
                        if (primary.size < 5 && variants.size > 1) {
                            tracks += openverse(variants[1])
                        }
                    }
                    MusicSource.COMMONS -> tracks += commons(q)
                    MusicSource.ARCHIVE -> tracks += archive(q)
                }
                if (networkAllowed && networkRequests > networkBefore) {
                    monitor.success(source, tracks.size - before)
                } else if (networkAllowed) {
                    monitor.cached(source, tracks.size - before)
                } else {
                    progress("${source.label}: использованы сохранённые результаты")
                }
            } catch (e: CacheMissException) {
                progress("${source.label}: новых запросов пока нет, результаты из кеша неполные")
            } catch (e: RateLimitException) {
                monitor.limited(source, e.retryAfter, e.httpCode)
                progress("${source.label}: временный лимит, переключаюсь на другой каталог…")
            } catch (e: Exception) {
                monitor.failure(source, e.message ?: "соединение недоступно")
                progress("${source.label}: нет ответа, продолжаю с другими источниками…")
            }
        }
        val unique = tracks.filter { it.audioUrl.startsWith("https://") }
            .distinctBy { it.audioUrl.substringBefore('?').lowercase(Locale.ROOT) }
            .take(65)
        progress("Найдено: ${unique.size}. Ответов из кеша: $cacheHits · запросов в интернет: $networkRequests. Состояние источников — ниже.")
        return unique
    }

    private fun openverse(query: String): List<Song> {
        val json = getJson("https://api.openverse.org/v1/audio/?q=${encoded(query)}&page_size=15")
        val results = json.optJSONArray("results") ?: JSONArray()
        return buildList {
            for (i in 0 until results.length()) {
                val x = results.optJSONObject(i) ?: continue
                val direct = x.optString("url")
                if (!direct.startsWith("https://")) continue
                val license = x.optString("license")
                val page = x.optString("foreign_landing_url").ifBlank { direct }
                add(Song(
                    title = x.optString("title").ifBlank { "Без названия" },
                    artist = x.optString("creator").ifBlank { "Не указан" },
                    audioUrl = direct,
                    pageUrl = page,
                    provider = "Openverse / ${x.optString("source")}",
                    license = license.ifBlank { "Не указана" },
                    licenseUrl = x.optString("license_url"),
                    verifiedDownload = license.lowercase(Locale.ROOT) in
                        setOf("cc0", "pdm", "by", "by-sa", "by-nc", "by-nc-sa", "by-nd", "by-nc-nd"),
                    format = x.optString("filetype").ifBlank { "audio" },
                    durationMs = x.optLong("duration", 0L).coerceAtLeast(0L)
                ))
            }
        }
    }

    private fun commons(query: String): List<Song> {
        val url = "https://commons.wikimedia.org/w/api.php" +
            "?action=query&generator=search&gsrsearch=${encoded(query)}&gsrnamespace=6" +
            "&gsrlimit=25&prop=imageinfo&iiprop=url%7Cmime%7Cextmetadata&format=json"
        val pages = getJson(url).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val found = mutableListOf<Song>()
        for (key in pages.keys()) {
            val file = pages.optJSONObject(key) ?: continue
            val info = file.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val mime = info.optString("mime")
            if (!mime.startsWith("audio/")) continue
            val direct = info.optString("url")
            if (!direct.startsWith("https://")) continue
            val metadata = info.optJSONObject("extmetadata") ?: JSONObject()
            val license = metadata.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
            val licUrl = metadata.optJSONObject("LicenseUrl")?.optString("value").orEmpty()
            val title = file.optString("title").removePrefix("File:")
            found += Song(
                title = title,
                artist = "Автор указан на странице файла",
                audioUrl = direct,
                pageUrl = info.optString("descriptionurl").ifBlank { direct },
                provider = "Wikimedia Commons",
                license = license.ifBlank { "См. страницу файла" },
                licenseUrl = licUrl,
                verifiedDownload = license.isNotBlank() && !license.contains("unknown", true),
                format = mime.substringAfter('/'),
            )
        }
        return found
    }

    /** Internet Archive content is NOT automatically public domain: check license metadata. */
    /** Archive metadata uses seconds, decimal seconds or colon-separated times. */
    private fun archiveLengthMs(input: String): Long {
        val s = input.trim()
        if (s.isEmpty()) return 0L
        val parts = s.split(':')
        if (parts.size !in 1..3) return 0L
        return try {
            var seconds = 0.0
            for (part in parts) {
                val value = part.toDouble()
                if (!value.isFinite() || value < 0.0) return 0L
                seconds = seconds * 60.0 + value
            }
            if (!seconds.isFinite() || seconds > 24.0 * 3600.0 * 10.0) 0L
            else (seconds * 1000.0).toLong()
        } catch (_: NumberFormatException) { 0L }
    }

    private fun archive(query: String): List<Song> {
        val phrase = query.split(Regex("\\s+")).take(6).joinToString(" ")
        val search = getJson("https://archive.org/advancedsearch.php" +
            "?q=${encoded("mediatype:audio AND $phrase")}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&rows=4&output=json")
        val docs = search.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val found = mutableListOf<Song>()
        for (i in 0 until minOf(docs.length(), 4)) {
            val doc = docs.optJSONObject(i) ?: continue
            val id = doc.optString("identifier")
            if (id.isBlank()) continue
            try {
                val info = getJson("https://archive.org/metadata/${encoded(id)}")
                val metadata = info.optJSONObject("metadata") ?: JSONObject()
                val license = metadata.optString("licenseurl")
                // If the rights field is absent, playback is allowed only when the file is
                // publicly exposed; download requires explicit user confirmation.
                val files = info.optJSONArray("files") ?: JSONArray()
                for (j in 0 until files.length()) {
                    val file = files.optJSONObject(j) ?: continue
                    val filename = file.optString("name")
                    val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in setOf("mp3", "ogg", "oga", "flac", "wav", "m4a")) continue
                    if (file.optString("source") == "metadata") continue
                    val direct = "https://archive.org/download/${encoded(id)}/${encoded(filename)}"
                    found += Song(
                        title = file.optString("title").ifBlank {
                            doc.optString("title").ifBlank { filename }
                        },
                        artist = doc.optString("creator").ifBlank { "Не указан" },
                        audioUrl = direct,
                        pageUrl = "https://archive.org/details/${encoded(id)}",
                        provider = "Internet Archive",
                        license = license.ifBlank { "Права не указаны — проверь источник" },
                        licenseUrl = license,
                        verifiedDownload = license.contains("creativecommons.org/licenses/") ||
                            license.contains("creativecommons.org/publicdomain/"),
                        format = ext,
                        durationMs = archiveLengthMs(file.optString("length"))
                    )
                    if (found.size >= 12) return found
                }
            } catch (limit: RateLimitException) { throw limit }
              catch (_: Exception) { /* Skip a bad record; continue politely. */ }
        }
        return found
    }
}
