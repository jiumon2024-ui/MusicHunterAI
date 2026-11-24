package com.musichunter.ai

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Public music catalogues supported by the free, on-device agent. */
enum class MusicSource(val label: String) {
    OPENVERSE("Openverse"), COMMONS("Wikimedia Commons"), ARCHIVE("Internet Archive")
}

enum class SourcePhase { WAITING, SEARCHING, READY, CACHED, LIMITED, ERROR }

data class SourceInfo(
    val source: MusicSource,
    val phase: SourcePhase,
    val coolDownUntil: Long = 0L,
    val retryIsExact: Boolean = false,
    val message: String = "",
    val found: Int = 0
)

class RateLimitException(val retryAfter: String?, val httpCode: Int) : Exception("HTTP $httpCode")

/**
 * Reads HTTP Retry-After as seconds or an HTTP date. Null means the server did not
 * supply a usable time. No fabricated 'remaining search credits' are displayed.
 */
object RetryHeaders {
    fun waitMillis(header: String?, now: Long): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { seconds ->
            return seconds.coerceIn(1L, 86_400L) * 1000L
        }
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val date = format.parse(value)?.time ?: return null
            (date - now).coerceIn(1000L, 86_400_000L)
        } catch (_: Exception) { null }
    }
}

/**
 * Persists each provider's cooldown across app restarts, honours Retry-After and
 * slows further retries (60s, 120s, 240s...) when a provider keeps refusing us.
 * No proxy, IP rotation or other limit evasion is attempted.
 */
class SourceMonitor(context: Context) {
    private val prefs = context.getSharedPreferences("music_source_health_v1", Context.MODE_PRIVATE)
    private val states = mutableMapOf<MusicSource, SourceInfo>()

    init {
        for (source in MusicSource.values()) {
            val until = prefs.getLong("${source.name}.until", 0L)
            val exact = prefs.getBoolean("${source.name}.exact", false)
            val attempts = prefs.getInt("${source.name}.attempts", 0)
            val phase = if (until > System.currentTimeMillis()) SourcePhase.LIMITED else SourcePhase.WAITING
            states[source] = SourceInfo(
                source = source, phase = phase, coolDownUntil = until,
                retryIsExact = exact,
                message = if (phase == SourcePhase.LIMITED) {
                    if (exact) "Ожидаем разрешённого повтора" else "Время снятия лимита неизвестно"
                } else if (attempts > 0) "Можно повторить проверку" else "Ещё не проверен"
            )
        }
    }

    @Synchronized fun get(source: MusicSource): SourceInfo = states.getValue(source)
    @Synchronized fun all(): List<SourceInfo> = MusicSource.values().map { states.getValue(it) }

    @Synchronized fun canRequest(source: MusicSource, now: Long = System.currentTimeMillis()): Boolean =
        get(source).phase != SourcePhase.LIMITED || get(source).coolDownUntil <= now

    @Synchronized fun checking(source: MusicSource) {
        states[source] = get(source).copy(phase = SourcePhase.SEARCHING, message = "Идёт поиск…")
    }

    @Synchronized fun success(source: MusicSource, found: Int) {
        states[source] = SourceInfo(source, SourcePhase.READY, message = "Источник отвечает", found = found)
        prefs.edit().remove("${source.name}.until").remove("${source.name}.exact")
            .remove("${source.name}.attempts").apply()
    }

    @Synchronized fun cached(source: MusicSource, found: Int) {
        states[source] = get(source).copy(phase = SourcePhase.CACHED,
            message = "Ответ из кеша — состояние сервера не проверялось", found = found)
    }

    @Synchronized fun failure(source: MusicSource, message: String) {
        states[source] = get(source).copy(phase = SourcePhase.ERROR,
            message = "Ошибка: ${message.take(90)}")
    }

    @Synchronized fun limited(source: MusicSource, header: String?, httpCode: Int): SourceInfo {
        val now = System.currentTimeMillis()
        val exactWait = RetryHeaders.waitMillis(header, now)
        val attempts = (prefs.getInt("${source.name}.attempts", 0) + 1).coerceAtMost(6)
        val fallbackWait = (60_000L * (1L shl (attempts - 1))).coerceAtMost(15 * 60_000L)
        // Never retry earlier than the provider's Retry-After.
        val millis = maxOf(exactWait ?: 0L, fallbackWait)
        val until = now + millis
        val info = SourceInfo(source, SourcePhase.LIMITED, until, exactWait != null,
            if (exactWait != null) "HTTP $httpCode · пауза по Retry-After (не раньше)"
            else "HTTP $httpCode · рекомендованная пауза, точное время неизвестно")
        states[source] = info
        prefs.edit().putLong("${source.name}.until", until)
            .putBoolean("${source.name}.exact", exactWait != null)
            .putInt("${source.name}.attempts", attempts).apply()
        return info
    }

    /** A cooldown expiry only makes a test eligible; it does not prove the site is unblocked. */
    @Synchronized fun refreshing(now: Long = System.currentTimeMillis()): List<MusicSource> {
        val due = mutableListOf<MusicSource>()
        for (source in MusicSource.values()) {
            val old = get(source)
            if (old.phase == SourcePhase.LIMITED && now >= old.coolDownUntil) {
                states[source] = old.copy(phase = SourcePhase.WAITING,
                    message = "Пауза завершилась · можно проверить снова")
                due += source
            }
        }
        return due
    }

    companion object {
        fun remainingText(milliseconds: Long): String {
            val seconds = ((milliseconds.coerceAtLeast(0L) + 999L) / 1000L)
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, mins, secs)
                   else "%02d:%02d".format(Locale.ROOT, mins, secs)
        }
    }
}
