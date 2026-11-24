package com.musichunter.ai

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale

/**
 * Polite, bounded, zero-cost exploratory crawler for a website opened by user.
 * Not a search-index crawler; deliberately refuses search engine result pages,
 * follows only same-host HTTPS HTML pages, respects basic robots.txt Disallow
 * directives, doesn't log in or evade security protections.
 */
class SiteExplorer {
    private val audioSuffix = Regex("(?i)\\.(mp3|ogg|oga|wav|flac|m4a)(?:[?#]|$)")
    private val researchTerms = listOf("audio", "track", "music", "song", "listen", "sound", "album", "remix", "музы", "песн", "аудио", "трек")
    private val blockedHosts = setOf(
        "google.com", "www.google.com", "bing.com", "www.bing.com",
        "duckduckgo.com", "www.duckduckgo.com", "yandex.ru", "yandex.com",
        "www.yandex.ru", "www.yandex.com"
    )

    fun explore(start: String, question: String, progress: (String) -> Unit): List<Song> {
        val uri = URI(start)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return emptyList()
        if (uri.scheme != "https" || host in blockedHosts || host == "localhost" ||
            host.endsWith(".local") || isIpLiteral(host)) {
            progress("Открой музыкальный сайт из поиска. Поисковики и локальные адреса не обходим автоматически.")
            return emptyList()
        }
        val root = "https://$host"
        val disallows = robots(root)
        if (disallows == null) {
            progress("Не удалось проверить robots.txt сайта. Автоматический обход отменён.")
            return emptyList()
        }
        val seen = mutableSetOf<String>()
        val found = linkedMapOf<String, Song>()
        val queued = ArrayDeque<Pair<String, Int>>()
        queued.add(start to 0)
        val terms = question.split(Regex("\\s+")).map { it.lowercase(Locale.ROOT) }
            .filter { it.length > 2 }.take(8)
        var pages = 0

        while (queued.isNotEmpty() && pages < 12 && found.size < 35) {
            val (current, depth) = queued.removeFirst()
            val target = try { URI(current) } catch (_: Exception) { continue }
            if (target.scheme != "https" || target.host?.lowercase(Locale.ROOT) != host) continue
            if (!seen.add(current.substringBefore('#'))) continue
            val path = (target.rawPath ?: "/").ifEmpty { "/" }
            if (disallows.any { it.isNotEmpty() && path.startsWith(it) }) continue
            progress("Веб-агент исследует страницы: ${pages + 1}/12 · $host")
            val document: Document = try {
                Jsoup.connect(current)
                    .userAgent("MusicHunterAI/0.1 (polite limited site explorer)")
                    .timeout(8000).maxBodySize(700_000)
                    .followRedirects(true).get()
            } catch (_: Exception) { continue }
            if (try { URI(document.location()).host?.lowercase(Locale.ROOT) != host } catch (_: Exception) { true }) continue
            pages++
            val title = document.title().ifBlank { "Найденное аудио" }.take(110)
            val candidates = linkedSetOf<String>()
            document.select("audio[src], audio source[src], video source[src]").forEach {
                candidates.add(it.absUrl("src"))
            }
            document.select("meta[property=og:audio], meta[property=og:audio:url], meta[name=twitter:player:stream]").forEach {
                val src = it.attr("content")
                candidates.add(try { URI(current).resolve(src).toString() } catch (_: Exception) { "" })
            }
            document.select("a[href]").forEach {
                val href = it.absUrl("href")
                if (audioSuffix.containsMatchIn(href)) candidates.add(href)
            }
            for (candidate in candidates) {
                if (candidate.isBlank() || found.containsKey(candidate)) continue
                val audioUri = try { URI(candidate) } catch (_: Exception) { continue }
                if (audioUri.scheme != "https" || audioUri.host == null ||
                    isIpLiteral(audioUri.host) || audioUri.host.equals("localhost", true)) continue
                val format = audioUri.path.substringAfterLast('.', "audio").lowercase(Locale.ROOT).take(8)
                found[candidate] = Song(
                    title = title, artist = "Проверить исполнителя на сайте",
                    audioUrl = candidate, pageUrl = current,
                    provider = "$host (веб-агент)",
                    license = "Права не проверены — см. источник",
                    verifiedDownload = false,
                    format = format
                )
            }
            if (depth >= 2) continue
            // Choose a few promising same-domain links, like a human following music-related pages.
            val links = document.select("a[href]").mapNotNull { a ->
                val href = a.absUrl("href")
                val targetUri = try { URI(href) } catch (_: Exception) { return@mapNotNull null }
                if (targetUri.scheme != "https" || targetUri.host?.lowercase(Locale.ROOT) != host ||
                    href.contains("logout", true) || href.contains("signout", true) ||
                    href.contains("delete", true) || audioSuffix.containsMatchIn(href)) return@mapNotNull null
                val description = (href + " " + a.text()).lowercase(Locale.ROOT)
                val score = researchTerms.count { description.contains(it) } * 2 +
                    terms.count { description.contains(it) }
                if (score < 1) null else (href to score)
            }.sortedByDescending { it.second }.take(8)
            links.forEach { (href, _) ->
                if (!seen.contains(href.substringBefore('#')) && queued.size < 24) queued.add(href to (depth + 1))
            }
        }
        progress("Веб-агент изучил $pages страниц сайта $host; аудио: ${found.size}. Для остальных сайтов открой их через веб-поиск.")
        return found.values.toList()
    }

    /** Null means uncertainty — do not crawl on robots fetch errors. */
    private fun robots(root: String): List<String>? {
        return try {
            val response = Jsoup.connect("$root/robots.txt")
                .userAgent("MusicHunterAI/0.1 (polite limited site explorer)")
                .ignoreContentType(true).ignoreHttpErrors(true)
                .timeout(5000).maxBodySize(100_000).execute()
            when (response.statusCode()) {
                404, 410 -> emptyList()
                200 -> {
                    var applies = false
                    val paths = mutableListOf<String>()
                    for (line in response.body().lineSequence()) {
                        val clean = line.substringBefore('#').trim()
                        val key = clean.substringBefore(':', "").lowercase(Locale.ROOT)
                        val value = clean.substringAfter(':', "").trim()
                        when (key) {
                            "user-agent" -> applies = value == "*" || value.equals("MusicHunterAI", true)
                            "disallow" -> if (applies && value.startsWith('/')) paths.add(value)
                        }
                    }
                    paths
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("[0-9.]+")) || host.contains(':')
}
