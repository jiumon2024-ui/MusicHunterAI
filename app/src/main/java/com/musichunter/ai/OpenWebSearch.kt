package com.musichunter.ai

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** Web-indexed page, not an authorized audio download or stream. */
data class WebHit(val title: String, val url: String, val snippet: String, val host: String)
data class WebLookup(val hits: List<WebHit>, val message: String)

/**
 * Optional keyless web discovery via a public search-engine RSS response.
 * No guarantee the endpoint remains available or covers every page on the web.
 * A URL here is only a pointer to a page: playback/download permissions
 * are assessed separately, not inferred from the search result.
 */
class OpenWebSearch(private val cache: SearchCache) {
    private val musicTranslations = linkedMapOf(
        "электронная музыка" to "electronic music",
        "электроника" to "electronic music",
        "ремиксы" to "remixes",
        "ремикс" to "remix",
        "инструментал" to "instrumental",
        "без слов" to "instrumental",
        "джаз" to "jazz",
        "рок" to "rock music",
        "поп" to "pop music",
        "диско" to "disco music",
        "танцевальная музыка" to "dance music",
        "классическая музыка" to "classical music",
        "electronic" to "электронная музыка",
        "jazz" to "джаз",
        "disco" to "диско",
        "remix" to "ремиксы",
        "instrumental" to "инструментал"
    )

    fun search(input: String, progress: (String) -> Unit): WebLookup {
        val base = AgentPlanner.variants(input).firstOrNull().orEmpty().ifBlank { input.trim() }
        val extra = translateGenre(base)
        val queries = linkedSetOf(base)
        // Search the original wording first. For known genres, search the other
        // language too; for a specific title keep its exact spelling intact.
        if (extra != null && !extra.equals(base, true)) queries += extra
        else if (base.split(Regex("\\s+")).size <= 5) {
            queries += if (base.any { it in 'а'..'я' || it in 'А'..'Я' })
                "$base слушать музыку" else "$base music audio"
        }
        val links = linkedMapOf<String, WebHit>()
        val problems = mutableListOf<String>()
        var cached = 0
        var network = 0
        for (phrase in queries.take(2)) {
            val url = "https://www.bing.com/search?q=${Uri.encode(phrase)}&format=rss&count=20"
            progress("Ищу в веб-индексе: $phrase")
            try {
                val saved = cache.read(url)
                val items = if (saved != null) {
                    cached++
                    decode(saved)
                } else {
                    network++
                    val fetched = fetch(url)
                    val array = JSONArray()
                    fetched.forEach {
                        array.put(JSONObject().put("title", it.title)
                            .put("url", it.url).put("summary", it.snippet).put("host", it.host))
                    }
                    if (fetched.isNotEmpty()) cache.write(url, JSONObject().put("results", array))
                    fetched
                }
                items.forEach { links.putIfAbsent(it.url.substringBefore('#'), it) }
            } catch (e: WebSearchLimit) {
                problems += "Веб-поиск временно ограничен (HTTP ${e.status})"
                // Respect provider limits: do not retry with another query.
                break
            } catch (e: Exception) {
                problems += "Веб-источник не ответил: ${e.message?.take(65) ?: "сбой"}"
                // A second independent query will not repair blocked connectivity.
                break
            }
        }
        val message = "Веб-страниц: ${links.size} · из кеша: $cached · обращений: $network" +
            if (problems.isEmpty()) "" else ". ${problems.first()}"
        progress(message)
        return WebLookup(links.values.take(40), message)
    }

    private fun translateGenre(input: String): String? {
        val lower = input.lowercase(Locale.ROOT)
        val match = musicTranslations.entries.firstOrNull { (key, _) ->
            lower == key || lower.contains("$key ") || lower.contains(" $key")
        } ?: return null
        return lower.replace(match.key, match.value)
    }

    private fun fetch(url: String): List<WebHit> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6500
            readTimeout = 9000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml;q=0.9")
            setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; MusicHunterAI/0.9; RSS music discovery)")
        }
        return try {
            val code = conn.responseCode
            if (code == 429 || code == 503) throw WebSearchLimit(code)
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val contentType = conn.contentType.orEmpty().lowercase(Locale.ROOT)
            if (contentType.contains("text/html")) throw IllegalStateException("поисковик вернул HTML вместо RSS")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().take(650_000)
            }
            val rss = Jsoup.parse(body, "https://www.bing.com/", Parser.xmlParser())
            val items = rss.select("item")
            if (items.isEmpty()) throw IllegalStateException("RSS-выдача не предоставлена")
            items.take(25).mapNotNull { element ->
                val link = element.selectFirst("link")?.text()?.trim().orEmpty()
                val valid = safeHost(link) ?: return@mapNotNull null
                val title = element.selectFirst("title")?.text()?.trim().orEmpty().take(180)
                if (title.isBlank()) return@mapNotNull null
                val description = element.selectFirst("description")?.text().orEmpty()
                WebHit(title, link, Jsoup.parse(description).text().take(260), valid)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(json: JSONObject): List<WebHit> {
        val arr = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url")
                val host = safeHost(url) ?: continue
                add(WebHit(item.optString("title").take(180), url,
                    item.optString("summary").take(260), host))
            }
        }
    }

    /** Don't send in-app browser to local/private literal hosts from third-party results. */
    private fun safeHost(link: String): String? {
        val uri = try { URI(link) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme != "https" || uri.userInfo != null ||
            host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") ||
            host.matches(Regex("[0-9.]+")) || host.contains(':')) return null
        return host
    }
    private class WebSearchLimit(val status: Int) : Exception("HTTP $status")
}
