package com.musichunter.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.widget.CheckBox
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONTokener
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Music Hunter AI: free experimental on-device rules-based research assistant.
 * It searches multiple open catalogs; the web browser scans ONLY loaded public
 * pages' DOM for HTML5 audio/direct audio links. No site bypass or fake 'AI'.
 */
class MainActivity : Activity() {
    private val background = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private lateinit var resultScroll: ScrollView
    private lateinit var browserPanel: LinearLayout
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPause: Button
    private lateinit var download: Button
    private lateinit var searchButton: Button
    private lateinit var monitor: SourceMonitor
    private lateinit var searchCache: SearchCache
    private lateinit var sourceOverview: TextView
    private lateinit var webIndexStatus: TextView
    private lateinit var sourceDetails: LinearLayout
    private lateinit var sourceDetailsButton: Button
    private val sourceTextViews = linkedMapOf<MusicSource, TextView>()
    private val retryPending = linkedSetOf<MusicSource>()
    private var activeQuery = ""
    private var isSearching = false
    private var previousSourceRefresh = 0L
    private val known = linkedSetOf<String>()
    private val knownWeb = linkedSetOf<String>()
    private val songDurationViews = linkedMapOf<String, TextView>()
    private val measuredDurations = linkedMapOf<String, Long>()
    private var installAfterPermission = false
    private var selected: Song? = null
    private var dragging = false
    private var browserOpen = false
    private var pendingDownload: Song? = null
    private val storagePermissionRequest = 1001
    private val updatePermissionRequest = 1002
    private lateinit var updateStatus: TextView
    private var pendingUpdate: AppRelease? = null
    private var checkingUpdates = false
    private var updaterReceiverRegistered = false
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val expected = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
                .getLong(ReleaseUpdates.DOWNLOAD_ID, -2)
            if (id != expected) return
            when (ReleaseUpdates.getDownloadState(this@MainActivity, id)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    updateStatus.text = "Обновление загружено · можно установить"
                    promptInstallIfNeeded()
                }
                DownloadManager.STATUS_FAILED -> {
                    updateStatus.text = "Не удалось скачать обновление"
                    toast("Загрузка обновления не удалась. Проверь интернет и память телефона.")
                }
            }
        }
    }

    private val timer = object : Runnable {
        override fun run() {
            updatePlaybackUi()
            val now = System.currentTimeMillis()
            if (now - previousSourceRefresh >= 1000L) {
                previousSourceRefresh = now
                monitor.refreshing(now)
                renderSources(now)
                retryEligibleSources()
            }
            handler.postDelayed(this, 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 16, 32)
        window.navigationBarColor = Color.rgb(10, 16, 32)
        setContentView(R.layout.activity_main)
        // Android 15+ enforces edge-to-edge for targetSdk 35. Keep the
        // header below the status bar and the controls above system navigation.
        // On older Android versions the insets are normally zero when the
        // decor already fits inside the system bars.
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                dp(10) + insets.systemWindowInsetLeft,
                dp(5) + insets.systemWindowInsetTop,
                dp(10) + insets.systemWindowInsetRight,
                dp(6) + insets.systemWindowInsetBottom
            )
            insets
        }
        root.requestApplyInsets()

        query = findViewById(R.id.query)
        results = findViewById(R.id.results)
        resultScroll = findViewById(R.id.resultScroll)
        browserPanel = findViewById(R.id.browserPanel)
        webView = findViewById(R.id.webView)
        status = findViewById(R.id.status)
        nowPlaying = findViewById(R.id.nowPlaying)
        position = findViewById(R.id.position)
        duration = findViewById(R.id.duration)
        seekBar = findViewById(R.id.seekBar)
        playPause = findViewById(R.id.playPause)
        download = findViewById(R.id.download)
        searchButton = findViewById(R.id.searchButton)
        monitor = SourceMonitor(this)
        searchCache = SearchCache(this)
        sourceOverview = findViewById(R.id.sourceOverview)
        webIndexStatus = findViewById(R.id.webIndexStatus)
        sourceDetails = findViewById(R.id.sourceDetailsPanel)
        sourceDetailsButton = findViewById(R.id.sourceDetailsButton)
        sourceTextViews[MusicSource.OPENVERSE] = findViewById(R.id.openverseState)
        sourceTextViews[MusicSource.COMMONS] = findViewById(R.id.commonsState)
        sourceTextViews[MusicSource.ARCHIVE] = findViewById(R.id.archiveState)
        sourceDetailsButton.setOnClickListener {
            val expanded = sourceDetails.visibility == View.VISIBLE
            sourceDetails.visibility = if (expanded) View.GONE else View.VISIBLE
            sourceDetailsButton.text = if (expanded) "Источники ▾" else "Свернуть ▴"
        }
        renderSources(System.currentTimeMillis())
        updateStatus = findViewById(R.id.updateStatus)
        findViewById<Button>(R.id.updateButton).setOnClickListener { showUpdateSettings() }
        val updatePrefs = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
        updateStatus.text = if (updatePrefs.getString(ReleaseUpdates.REPO, BuildConfig.UPDATES_REPO).isNullOrBlank())
            "Укажи GitHub для автообновлений" else "Обновления по Wi-Fi и интернету активны"
        UpdateJobs.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
        updaterReceiverRegistered = true

        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Не удалось воспроизвести файл: ${error.errorCodeName}. Проверь ссылку/ограничения сайта."
            }
            override fun onPlaybackStateChanged(playbackState: Int) { updatePlaybackUi() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlaybackUi() }
        })

        searchButton.setOnClickListener { startAgent() }
        findViewById<Button>(R.id.chipElectronic).setOnClickListener { query.setText("электронная музыка"); startAgent() }
        findViewById<Button>(R.id.chipRemix).setOnClickListener { query.setText("remix dance"); startAgent() }
        findViewById<Button>(R.id.chipInstrumental).setOnClickListener { query.setText("instrumental"); startAgent() }
        findViewById<Button>(R.id.myDownloads).setOnClickListener {
            try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            catch (_: Exception) { toast("Открой приложение «Файлы» и папку Музыка") }
        }
        findViewById<Button>(R.id.closeBrowser).setOnClickListener { closeBrowser() }
        findViewById<Button>(R.id.scanBrowser).setOnClickListener { scanPage(false) }
        findViewById<Button>(R.id.exploreSite).setOnClickListener { exploreSite() }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { startAgent(); true } else false
        }
        playPause.isEnabled = false
        download.isEnabled = false
        playPause.setOnClickListener {
            val track = selected ?: return@setOnClickListener
            if (player.mediaItemCount == 0) play(track)
            else if (player.isPlaying) player.pause() else player.play()
        }
        download.setOnClickListener { selected?.let { askDownload(it) } }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val length = player.duration
                if (length > 0) player.seekTo((length * seekBar.progress / 1000L).coerceAtMost(length))
                dragging = false
            }
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    position.text = time(player.duration * value / 1000L)
                }
            }
        })

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.setSupportMultipleWindows(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme.orEmpty()
                return scheme != "https" && scheme != "about"
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (browserOpen && url?.startsWith("https://") == true) {
                    handler.postDelayed({ if (browserOpen) scanPage(true) }, 950)
                }
            }
        }
        showWelcome()
    }

    private fun startAgent() {
        if (isSearching) { toast("Поиск уже выполняется"); return }
        val text = query.text.toString().trim()
        if (text.isBlank()) { toast("Напиши название или жанр"); return }
        if (text.startsWith("https://")) { openBrowser(text); return }
        activeQuery = text
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
        retryPending.clear()
        closeBrowser()
        known.clear()
        knownWeb.clear()
        songDurationViews.clear()
        results.removeAllViews()
        runCatalogSearch(text, MusicSource.values().toSet(), false)
    }

    /** One search in Russian or English: playable catalog audio and indexed web pages. */
    private fun runCatalogSearch(text: String, sources: Set<MusicSource>, retry: Boolean) {
        if (isSearching || sources.isEmpty()) return
        isSearching = true
        searchButton.isEnabled = false
        status.text = if (retry) "Повторно проверяю доступные каталоги…"
                      else "Ищу музыку в каталогах и по открытому интернету…"
        background.execute {
            try {
                val songs = AgentSearch(monitor, searchCache).search(text, sources) { update ->
                    runOnUiThread { status.text = update; renderSources(System.currentTimeMillis()) }
                }
                // A broad web index is searched automatically. This does not imply that
                // the linked sites allow in-app playback or downloading.
                val web = if (!retry) try {
                    OpenWebSearch(searchCache).search(text) { update ->
                        runOnUiThread { status.text = update }
                    }
                } catch (e: Exception) {
                    WebLookup(emptyList(), "Веб-поиск недоступен: ${e.message?.take(70)}")
                } else WebLookup(emptyList(), "")

                val directLinks = if (retry) emptyList() else web.hits.filter { isDirectAudioLink(it.url) }
                val webPages = if (retry) emptyList() else web.hits.filterNot { isDirectAudioLink(it.url) }
                runOnUiThread {
                    isSearching = false
                    searchButton.isEnabled = true
                    if (!retry) {
                        webIndexStatus.text = "Веб-индекс · ${web.message}"
                        addParagraph("♫ АУДИО ДЛЯ ПРОСЛУШИВАНИЯ · ${songs.size + directLinks.size}", true)
                    }
                    songs.forEach { addSong(it) }
                    directLinks.forEach { hit ->
                        addSong(Song(
                            title = hit.title,
                            artist = "Результат веб-поиска · права проверь на сайте",
                            audioUrl = hit.url,
                            pageUrl = hit.url,
                            provider = hit.host,
                            license = "Не проверена",
                            verifiedDownload = false,
                            format = hit.url.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT)
                        ))
                    }
                    if (!retry) {
                        addParagraph("🌍 САЙТЫ И СТРАНИЦЫ С МУЗЫКОЙ · ${webPages.size}", true)
                        if (webPages.isEmpty()) {
                            addParagraph(web.message.ifBlank { "Веб-выдача сейчас недоступна. Попробуй поискать позже." }, false)
                        } else {
                            webPages.forEach { addWebHit(it) }
                        }
                        // Scrolling the full page is possible from any visible card.
                        resultScroll.post {
                            resultScroll.smoothScrollTo(0, (results.top - dp(6)).coerceAtLeast(0))
                        }
                        status.text = "Аудиофайлы: ${songs.size + directLinks.size} · веб-страницы: ${webPages.size}. " +
                            "Слушать/скачивать можно только доступные аудиозаписи."
                    } else {
                        status.text = "Повторная проверка завершена · новые аудиоссылки добавлены"
                    }
                    if (known.isEmpty() && web.hits.isEmpty()) showEmpty()
                    for (source in sources) {
                        if (monitor.get(source).phase == SourcePhase.LIMITED) retryPending += source
                    }
                    renderSources(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isSearching = false
                    searchButton.isEnabled = true
                    status.text = "Ошибка поиска: ${e.message?.take(120)}"
                    if (known.isEmpty()) showEmpty()
                    renderSources(System.currentTimeMillis())
                }
            }
        }
    }

    private fun isDirectAudioLink(url: String): Boolean =
        Regex("(?i)\\.(mp3|ogg|oga|wav|flac|m4a)(?:[?#]|$)").containsMatchIn(url)

    private fun addWebHit(hit: WebHit) {
        if (!knownWeb.add(hit.url)) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded("#15243E", 13f)
        }
        fun textLine(value: String, size: Float, color: Int, strong: Boolean = false) {
            card.addView(TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                typeface = Typeface.create("sans-serif", if (strong) Typeface.BOLD else Typeface.NORMAL)
                maxLines = if (strong) 2 else 3
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        textLine(hit.title, 12.5f, Color.WHITE, true)
        textLine(hit.host, 10f, Color.rgb(117, 234, 192))
        if (hit.snippet.isNotBlank()) textLine(hit.snippet, 10.5f, Color.rgb(192, 204, 226))
        card.addView(smallButton("↗ Открыть и проверить аудио") {
            openBrowser(hit.url)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(35)))
        results.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
    }

    /** Automatic retry happens only while the application screen is open. */
    private fun retryEligibleSources() {
        if (isSearching || activeQuery.isBlank() || retryPending.isEmpty()) return
        val due = retryPending.filter { source ->
            val info = monitor.get(source)
            info.phase == SourcePhase.WAITING &&
                (info.coolDownUntil == 0L || System.currentTimeMillis() >= info.coolDownUntil)
        }.toSet()
        if (due.isEmpty()) return
        retryPending.removeAll(due)
        status.text = "Лимитное ожидание завершилось. Проверяю: ${due.joinToString { it.label }}"
        runCatalogSearch(activeQuery, due, true)
    }

    private fun renderSources(now: Long) {
        if (!::sourceOverview.isInitialized) return
        val states = monitor.all()
        val blocked = states.count { it.phase == SourcePhase.LIMITED }
        val errors = states.count { it.phase == SourcePhase.ERROR }
        val ready = states.count { it.phase == SourcePhase.READY }
        val cached = states.count { it.phase == SourcePhase.CACHED }
        val busy = states.count { it.phase == SourcePhase.SEARCHING }
        val headline = when {
            blocked > 0 -> "● Ограничены: $blocked из 3 · остальные источники доступны для проверки"
            errors > 0 -> "● Источники с ошибкой: $errors из 3"
            busy > 0 -> "● Проверяю источники: $busy из 3"
            ready == 3 -> "● Все 3 источника отвечают"
            ready > 0 -> "● Ответили источники: $ready из 3${if (cached > 0) " · из кеша: $cached" else ""}"
            cached > 0 -> "● Показаны сохранённые результаты: $cached из 3"
            else -> "● Источники ещё не проверялись"
        }
        sourceOverview.text = headline
        sourceOverview.setTextColor(when {
            blocked > 0 -> Color.rgb(255, 197, 120)
            errors > 0 -> Color.rgb(255, 151, 151)
            ready > 0 -> Color.rgb(117, 234, 192)
            else -> Color.rgb(187, 173, 223)
        })
        for (info in states) {
            val label = when (info.phase) {
                SourcePhase.WAITING -> "○ Ожидает проверки · ${info.message}"
                SourcePhase.SEARCHING -> "◌ Выполняется поиск…"
                SourcePhase.READY -> "● Работает · найдено: ${info.found}"
                SourcePhase.CACHED -> "◉ Из кеша · найдено: ${info.found}, ответ сайта не проверялся"
                SourcePhase.ERROR -> "! ${info.message}"
                SourcePhase.LIMITED -> {
                    val wait = SourceMonitor.remainingText(info.coolDownUntil - now)
                    "◷ Ограничен · повтор не раньше $wait · ${info.message}"
                }
            }
            sourceTextViews[info.source]?.apply {
                text = "${info.source.label}\n$label"
                setTextColor(when (info.phase) {
                    SourcePhase.LIMITED -> Color.rgb(255, 198, 132)
                    SourcePhase.ERROR -> Color.rgb(255, 150, 155)
                    SourcePhase.READY -> Color.rgb(132, 234, 205)
                    SourcePhase.CACHED -> Color.rgb(169, 202, 247)
                    else -> Color.rgb(206, 195, 239)
                })
            }
        }
    }

    private fun showWelcome() {
        addParagraph("🎧 Что будем искать?", true)
        addParagraph("Напиши название, исполнителя или жанр по-русски или по-английски и нажми «Исследовать». Агент проверит аудиокаталоги и веб-выдачу одним запросом. Попробуй: электронная музыка, диско, jazz, remix.", false)
    }

    private fun showEmpty() {
        if (results.childCount == 0) addParagraph("Ничего не найдено. Проверь интернет или попробуй другое написание по-русски либо по-английски.", true)
    }

    private fun addParagraph(text: String, bold: Boolean) {
        val item = TextView(this).apply {
            this.text = text
            setTextColor(Color.rgb(198, 213, 238))
            textSize = if (bold) 13f else 12f
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        results.addView(item)
    }

    private fun addSong(song: Song) {
        if (!known.add(song.audioUrl)) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(28, 19, 55), Color.rgb(16, 27, 47))).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1).coerceAtLeast(1), Color.rgb(70, 51, 117))
            }
        }
        fun label(text: String, size: Float, color: Int, bold: Boolean = false) {
            card.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                maxLines = if (bold) 2 else 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(1))
            })
        }
        // Each result has its own timecode. We never invent missing duration.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = song.title
            textSize = 13.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val timeView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(158, 226, 249))
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
            text = songTimeLabel(song, isCurrent = selected?.audioUrl == song.audioUrl)
        }
        titleRow.addView(timeView)
        card.addView(titleRow)
        songDurationViews[song.audioUrl] = timeView
        label(song.artist, 11f, Color.rgb(202, 218, 245))
        label("${song.provider} · ${song.format.uppercase(Locale.ROOT)} · ${song.license}",
            10f, Color.rgb(196, 163, 240))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val playButton = smallButton("▶ Слушать") { play(song) }
        val saveButton = smallButton("⬇ Скачать") { askDownload(song) }
        val sourceButton = smallButton("↗ Источник") { openBrowser(song.pageUrl) }
        for (b in listOf(playButton, saveButton, sourceButton)) {
            actions.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        card.addView(actions)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(6)
        results.addView(card, params)
    }

    private fun smallButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 10f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        minHeight = 0
        minWidth = 0
        background = rounded("#31204F", 10f)
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun play(song: Song) {
        if (!song.audioUrl.startsWith("https://")) { toast("Нет прямой ссылки HTTPS"); return }
        val previous = selected
        selected = song
        if (previous != null && previous.audioUrl != song.audioUrl) {
            songDurationViews[previous.audioUrl]?.text = songTimeLabel(previous, false)
        }
        songDurationViews[song.audioUrl]?.text = songTimeLabel(song, true)
        playPause.isEnabled = true
        download.isEnabled = true
        nowPlaying.text = "${song.title} — ${song.artist}"
        player.setMediaItem(MediaItem.fromUri(song.audioUrl))
        player.prepare()
        player.play()
        position.text = "00:00"
        status.text = "Воспроизвожу из ${song.provider}. При ограничении доступа открой страницу источника."
    }

    private fun updatePlaybackUi() {
        if (!::player.isInitialized || !::seekBar.isInitialized) return
        val actualDuration = player.duration.takeIf { it > 0L }
        val currentSong = selected
        if (actualDuration != null && currentSong != null) {
            measuredDurations[currentSong.audioUrl] = actualDuration
        }
        val length = actualDuration ?: (currentSong?.let { measuredDurations[it.audioUrl] ?: it.durationMs } ?: 0L)
        val current = player.currentPosition.coerceAtLeast(0)
        if (currentSong != null) {
            val timeView = songDurationViews[currentSong.audioUrl]
            val newLabel = songTimeLabel(currentSong, true)
            if (timeView != null && timeView.text.toString() != newLabel) timeView.text = newLabel
        }
        if (!dragging) {
            seekBar.progress = if (length > 0) (current * 1000L / length).toInt().coerceIn(0, 1000) else 0
            position.text = time(current)
        }
        duration.text = time(length)
        playPause.text = if (player.isPlaying) "❚❚ Пауза" else "▶ Играть"
    }

    /** Duration from catalog or measured by ExoPlayer after playback starts. */
    private fun songTimeLabel(song: Song, isCurrent: Boolean): String {
        val total = measuredDurations[song.audioUrl] ?: song.durationMs
        val totalText = if (total > 0L) time(total) else "—:—"
        return if (isCurrent && ::player.isInitialized && selected?.audioUrl == song.audioUrl) {
            "${time(player.currentPosition.coerceAtLeast(0L))} / $totalText"
        } else totalText
    }

    private fun askDownload(song: Song) {
        if (!song.audioUrl.startsWith("https://")) { toast("Только прямые HTTPS-аудиоссылки"); return }
        val message = if (song.verifiedDownload)
            "Источник сообщает лицензию: ${song.license}. Перед использованием проверь условия: иногда требуется указать автора и запрещено коммерческое использование.\n\nСкачать в папку Music/MusicHunterAI?"
        else
            "Источник не подтвердил право на скачивание. Сохраняй запись только если владеешь правами или у тебя есть разрешение.\n\nПерейди на сайт автора, чтобы проверить условия. Подтверждаешь, что вправе скачать этот файл?"
        AlertDialog.Builder(this)
            .setTitle("Скачивание аудио")
            .setMessage(message)
            .setPositiveButton(if (song.verifiedDownload) "Скачать" else "Подтверждаю") { _, _ -> download(song) }
            .setNeutralButton("Открыть источник") { _, _ -> openBrowser(song.pageUrl) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun download(song: Song) {
        // Android 6-9 require runtime permission to write to shared Music/ folder.
        // Android 10+ supports public Media directories without this legacy permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = song
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), storagePermissionRequest)
            return
        }
        try {
            val extension = song.format.lowercase(Locale.ROOT).let {
                if (it in setOf("mp3", "ogg", "oga", "flac", "wav", "m4a", "mpeg")) {
                    if (it == "mpeg") "mp3" else it
                } else "mp3"
            }
            val name = (song.artist + " - " + song.title)
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(90).ifBlank { "track" }
            val request = DownloadManager.Request(Uri.parse(song.audioUrl)).apply {
                setTitle(song.title)
                setDescription("Music Hunter AI · ${song.provider}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "MusicHunterAI/$name.$extension")
                setAllowedOverMetered(true)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast("Загрузка началась · Music/MusicHunterAI")
        } catch (e: Exception) { toast("Ошибка загрузки: ${e.message}") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == updatePermissionRequest) {
            val update = pendingUpdate
            pendingUpdate = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                update?.let { downloadUpdate(it) }
            } else {
                toast("Для скачивания обновления нужен доступ к папке «Загрузки»")
            }
            return
        }
        if (requestCode != storagePermissionRequest) return
        val song = pendingDownload
        pendingDownload = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            song?.let { download(it) }
        } else {
            toast("Для сохранения в папку Музыка необходимо разрешение на файлы")
        }
    }

    /** The owner configures a public GitHub Releases repo once; no account/token is needed on phones. */
    private fun showUpdateSettings() {
        val prefs = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val description = TextView(this).apply {
            text = "Укажи публичный GitHub-репозиторий с выпусками MusicHunterAI.apk (владелец/репозиторий). Телефон проверяет версии и в фоне, когда интернет доступен. USB-кабель не нужен."
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val repoField = EditText(this).apply {
            setSingleLine(true)
            hint = "username/repository"
            setText(prefs.getString(ReleaseUpdates.REPO, BuildConfig.UPDATES_REPO))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSelectAllOnFocus(true)
        }
        val autoCheck = CheckBox(this).apply {
            text = "Скачивать новые версии автоматически"
            isChecked = prefs.getBoolean(ReleaseUpdates.AUTO_DOWNLOAD, true)
            setTextColor(Color.WHITE)
        }
        val safety = TextView(this).apply {
            text = "После загрузки Android попросит подтвердить установку. Обновление должно иметь ту же цифровую подпись, что и установленное приложение."
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        panel.addView(description)
        panel.addView(repoField)
        panel.addView(autoCheck)
        panel.addView(safety)
        AlertDialog.Builder(this)
            .setTitle("Обновления Music Hunter AI")
            .setView(panel)
            .setPositiveButton("Сохранить и проверить") { _, _ ->
                val repository = ReleaseUpdates.normalizeRepo(repoField.text.toString())
                if (repository == null) {
                    toast("Укажи корректный GitHub-репозиторий: username/repository")
                } else {
                    prefs.edit().putString(ReleaseUpdates.REPO, repository)
                        .putBoolean(ReleaseUpdates.AUTO_DOWNLOAD, autoCheck.isChecked).apply()
                    UpdateJobs.schedule(this)
                    checkForUpdates(force = true)
                }
            }
            .setNeutralButton(if (hasDownloadedUpdate()) "Установить" else "Загрузки") { _, _ ->
                if (hasDownloadedUpdate()) installDownloadedUpdate() else openSystemDownloads()
            }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun checkForUpdates(force: Boolean) {
        if (checkingUpdates) return
        val prefs = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
        val repo = prefs.getString(ReleaseUpdates.REPO, BuildConfig.UPDATES_REPO).orEmpty()
        if (repo.isBlank()) {
            if (force) toast("Сначала укажи репозиторий обновлений")
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(ReleaseUpdates.LAST_CHECK_AT, 0L) < 6 * 60 * 60 * 1000L) {
            displayStoredDownloadState()
            return
        }
        checkingUpdates = true
        updateStatus.text = "Проверяю новые версии…"
        background.execute {
            try {
                val release = ReleaseUpdates.latest(repo)
                runOnUiThread {
                    checkingUpdates = false
                    prefs.edit().putLong(ReleaseUpdates.LAST_CHECK_AT, now).apply()
                    val thisVersion = if (Build.VERSION.SDK_INT >= 28) {
                        packageManager.getPackageInfo(packageName, 0).longVersionCode
                    } else {
                        packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
                    }
                    if (release == null) {
                        updateStatus.text = "В GitHub пока нет доступного APK"
                    } else if (release.versionCode > thisVersion) {
                        updateStatus.text = "Доступна версия ${release.versionName}"
                        val downloadState = ReleaseUpdates.downloadStateFor(this, release.versionCode)
                        if (downloadState == DownloadManager.STATUS_SUCCESSFUL ||
                            downloadState == DownloadManager.STATUS_RUNNING ||
                            downloadState == DownloadManager.STATUS_PENDING) {
                            displayStoredDownloadState()
                        } else if (prefs.getBoolean(ReleaseUpdates.AUTO_DOWNLOAD, true)) {
                            downloadUpdate(release)
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Новая версия Music Hunter AI")
                                .setMessage("Доступна версия ${release.versionName}.\n\n${release.notes.take(300)}\n\nСкачать бесплатное обновление?")
                                .setPositiveButton("Скачать") { _, _ -> downloadUpdate(release) }
                                .setNegativeButton("Позже", null).show()
                        }
                    } else {
                        updateStatus.text = "Установлена актуальная версия"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    checkingUpdates = false
                    updateStatus.text = "Ошибка проверки обновлений"
                    if (force) toast("Не удалось проверить GitHub: ${e.message?.take(90)}")
                }
            }
        }
    }

    private fun downloadUpdate(release: AppRelease) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingUpdate = release
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), updatePermissionRequest)
            return
        }
        try {
            ReleaseUpdates.enqueueOnce(this, release)
            updateStatus.text = "Скачивается обновление ${release.versionName}…"
            toast("Обновление загружается · смотри уведомления Android")
        } catch (e: Exception) {
            updateStatus.text = "Ошибка скачивания обновления"
            toast("Не получилось скачать APK: ${e.message?.take(90)}")
        }
    }

    private fun displayStoredDownloadState() {
        val prefs = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
        val id = prefs.getLong(ReleaseUpdates.DOWNLOAD_ID, -1L)
        when (ReleaseUpdates.getDownloadState(this, id)) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                updateStatus.text = "Обновление загружено · нажми «Обновления» → «Установить»"
            }
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                updateStatus.text = "Загружается новая версия…"
            }
        }
    }

    private fun hasDownloadedUpdate(): Boolean {
        val prefs = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
        val version = prefs.getInt(ReleaseUpdates.DOWNLOAD_VERSION, -1)
        val id = prefs.getLong(ReleaseUpdates.DOWNLOAD_ID, -1L)
        return version > BuildConfig.VERSION_CODE &&
            ReleaseUpdates.getDownloadState(this, id) == DownloadManager.STATUS_SUCCESSFUL
    }

    private fun promptInstallIfNeeded() {
        if (!hasDownloadedUpdate() || isFinishing) return
        val prefs = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
        val id = prefs.getLong(ReleaseUpdates.DOWNLOAD_ID, -1L)
        if (id == prefs.getLong(ReleaseUpdates.PROMPTED_DOWNLOAD_ID, -1L)) return
        prefs.edit().putLong(ReleaseUpdates.PROMPTED_DOWNLOAD_ID, id).apply()
        AlertDialog.Builder(this)
            .setTitle("Новое обновление загружено")
            .setMessage("Установить Music Hunter AI по интернету, без провода? Android попросит подтвердить установку.")
            .setPositiveButton("Установить") { _, _ -> installDownloadedUpdate() }
            .setNegativeButton("Позже", null).show()
    }

    private fun installDownloadedUpdate() {
        if (!hasDownloadedUpdate()) {
            toast("Загруженного обновления пока нет")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            installAfterPermission = true
            AlertDialog.Builder(this)
                .setTitle("Разрешение на установку")
                .setMessage("Разреши установку из Music Hunter AI. Это системное разрешение Android для обновлений APK вне Google Play.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"))
                    try { startActivity(settings) } catch (_: Exception) { openSystemDownloads() }
                }
                .setNegativeButton("Отмена", null).show()
            return
        }
        try {
            val prefs = getSharedPreferences(ReleaseUpdates.PREFS, MODE_PRIVATE)
            val id = prefs.getLong(ReleaseUpdates.DOWNLOAD_ID, -1L)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val apkUri = manager.getUriForDownloadedFile(id)
                ?: throw IllegalStateException("Не удалось получить загруженный APK")
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(install)
        } catch (e: Exception) {
            toast("Не удалось открыть установщик: ${e.message?.take(70)}")
            openSystemDownloads()
        }
    }

    override fun onResume() {
        super.onResume()
        if (installAfterPermission && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls())) {
            installAfterPermission = false
            installDownloadedUpdate()
        }
    }

    private fun openSystemDownloads() {
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        catch (_: Exception) { toast("Открой папку «Загрузки» через приложение «Файлы»") }
    }

    private fun openBrowser(rawUrl: String) {
        if (!rawUrl.startsWith("https://")) { toast("Разрешены только HTTPS-страницы"); return }
        browserOpen = true
        browserPanel.visibility = View.VISIBLE
        resultScroll.visibility = View.GONE
        webView.loadUrl(rawUrl)
    }

    private fun closeBrowser() {
        browserOpen = false
        if (::browserPanel.isInitialized) browserPanel.visibility = View.GONE
        if (::resultScroll.isInitialized) resultScroll.visibility = View.VISIBLE
    }

    /** Deliberately follows only a small number of public same-host pages. */
    private fun exploreSite() {
        val page = webView.url.orEmpty()
        val input = query.text.toString()
        if (!page.startsWith("https://")) { toast("Открой HTTPS-сайт с музыкой"); return }
        status.text = "Веб-агент готовится исследовать сайт…"
        background.execute {
            try {
                val found = SiteExplorer().explore(page, input) { message ->
                    runOnUiThread { status.text = message }
                }
                runOnUiThread {
                    found.forEach { addSong(it) }
                    toast("Проверка завершена. Новых находок: ${found.size}")
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Веб-агент: ${e.message?.take(130)}" }
            }
        }
    }

    /** Inspect visible page DOM, never steal protected/DRM media or bypass auth. */
    private fun scanPage(automatic: Boolean) {
        if (!browserOpen) return
        val script = """
          (function () {
            const urls = [];
            const push = (value) => {
              if (!value) return;
              try {
                const u = new URL(value, document.baseURI);
                if (u.protocol === 'https:' && !urls.includes(u.href)) urls.push(u.href);
              } catch (e) {}
            };
            document.querySelectorAll('audio, audio source, video source').forEach(x => push(x.src || x.getAttribute('src')));
            document.querySelectorAll('meta[property="og:audio"], meta[property="og:audio:url"], meta[name="twitter:player:stream"]').forEach(x => push(x.content));
            document.querySelectorAll('a[href]').forEach(x => {
              const href = x.href || '';
              if (/\.(mp3|ogg|oga|wav|flac|m4a)(\?|#|${'$'})/i.test(href)) push(href);
            });
            return JSON.stringify({title: document.title || 'Аудио со страницы', urls: urls.slice(0, 12)});
          })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            try {
                val inner = JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                val json = org.json.JSONObject(inner)
                val title = json.optString("title").take(120)
                val urls = json.optJSONArray("urls") ?: JSONArray()
                var newFiles = 0
                for (i in 0 until urls.length()) {
                    val direct = urls.optString(i)
                    if (direct.isBlank() || known.contains(direct)) continue
                    val path = Uri.parse(direct).lastPathSegment.orEmpty().substringBefore('?')
                    val format = path.substringAfterLast('.', "audio").lowercase(Locale.ROOT)
                    val track = Song(
                        title = if (title.isBlank()) path.ifBlank { "Аудиозапись с сайта" } else title,
                        artist = "Обнаружено веб-агентом",
                        audioUrl = direct,
                        pageUrl = webView.url.orEmpty(),
                        provider = Uri.parse(webView.url).host.orEmpty(),
                        license = "Проверь права на странице источника",
                        verifiedDownload = false,
                        format = format
                    )
                    addSong(track)
                    newFiles++
                }
                if (newFiles > 0) {
                    status.text = "Веб-агент нашёл $newFiles аудиоссылок. Нажми «К результатам», чтобы прослушать."
                    if (automatic) toast("Обнаружено аудио: $newFiles")
                } else if (!automatic) {
                    toast("Прямых HTML5-аудиоссылок на этой странице не обнаружено")
                }
            } catch (_: Exception) { if (!automatic) toast("Не удалось прочитать содержимое страницы") }
        }
    }

    private fun time(ms: Long): String {
        val t = ms.coerceAtLeast(0L) / 1000L
        return if (t >= 3600L) String.format(Locale.ROOT, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
        else String.format(Locale.ROOT, "%02d:%02d", t / 60, t % 60)
    }
    private fun dp(pixels: Int) = (pixels * resources.displayMetrics.density).toInt()
    private fun rounded(hex: String, radius: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(hex))
        cornerRadius = dp(radius.toInt()).toFloat()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    @Deprecated("Use OnBackPressedDispatcher in an AndroidX Activity")
    override fun onBackPressed() {
        if (browserOpen && webView.canGoBack()) webView.goBack()
        else if (browserOpen) closeBrowser()
        else super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        handler.post(timer)
        UpdateJobs.schedule(this)
        displayStoredDownloadState()
        promptInstallIfNeeded()
        checkForUpdates(force = false)
    }

    override fun onStop() {
        handler.removeCallbacks(timer)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        if (updaterReceiverRegistered) unregisterReceiver(updateReceiver)
        background.shutdownNow()
        player.release()
        webView.destroy()
        super.onDestroy()
    }
}
