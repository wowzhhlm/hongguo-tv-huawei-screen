// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 随心看组件：组装分类栏、视频区和操作栏，并管理播放请求及遥控器焦点。

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.*
import java.io.Closeable
import java.util.concurrent.Executors

/** 首页连续播放，共享观看记录和播放偏好，保留独立的横向浏览状态。 */
internal class HomeFeedScreen(
    private val activity: Activity,
    private val state: DramaFeedState,
    private val nav: List<View>,
    private val watch: (Series) -> Unit,
    private val favorite: (Series, View) -> Unit,
    private val favoriteLabel: (String) -> String,
    private val backend: FeedBackend,
    private val playback: TrialPlayback,
    private val autoPreview: Boolean = true,
    private val backgroundBusy: () -> Boolean = { false },
    private val loadCover: (ImageView, String) -> Unit = { _, _ -> },
    private val related: (Series, View) -> Unit = { _, _ -> },
    private val autoNext: Boolean = true,
    private val restoreProgress: (String) -> WatchProgress? = { null },
    // 推荐流默认关闭：启动即随机从头播放，不读取观看历史续播。默认 true 兼容既有测试替身。
    private val restoreWatchProgress: Boolean = true,
    private val saveProgress: (WatchProgress, Int, Boolean) -> Unit = { _, _, _ -> },
    // 首页播放控制：默认 no-op，既有调用方与测试替身无需改动即可继续工作。
    private val initialSpeed: Float = 1f,
    private val initialQuality: Int = 1080,
    private val onSpeedChange: (Float) -> Unit = {},
    private val onQualityChange: (Int) -> Unit = {},
    private val openEpisodes: (Series) -> Unit = {},
    // 互动统计：返回真实评论数；无法获取时返回 null，界面不显示占位假数。默认关闭以保持测试替身兼容。
    private val socialStats: (Series, String) -> Long? = { _, _ -> null },
) : LinearLayout(activity), Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    // 互动数据探测是可选的慢网络读取（两次公开网页请求，最长 20 秒）。它与播放取流
    // 若共用 worker，网络拥塞时会占满 2 个线程，令播放请求排队超时、误报空响应并触发
    // 自动恢复。改用独立单线程池，保证社交探测永远不会阻塞播放/预加载。
    private val socialWorker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var scope: RequestScope? = null
    private var job: java.util.concurrent.Future<*>? = null
    private var prefetchScope: RequestScope? = null
    private var prefetchJob: java.util.concurrent.Future<*>? = null
    private var prefetchTarget: String? = null

    private data class CachedTrial(
        val clip: TrialClip,
        val savedAt: Long = android.os.SystemClock.elapsedRealtime(),
    )

    private val cachedTrials = linkedMapOf<String, CachedTrial>()
    private val attemptedPrefetch = mutableSetOf<String>()
    private val prefetchFailures = mutableMapOf<String, Int>()
    private var nextEpisode: TrialClip? = null
    // Keep the ended source alive until its replacement is attached (or the user leaves).
    private var heldEpisode: TrialClip? = null
    private var nextEpisodeAttempts = 0
    private var nextEpisodeRetryAt = 0L
    private var prefetchGeneration = 0
    private val catalogWorker = Executors.newSingleThreadExecutor()
    private var catalogScope: RequestScope? = null
    private var catalogJob: java.util.concurrent.Future<*>? = null
    private var catalogGeneration = 0
    private var catalogRetryAt = 0L
    private var catalogFailed = false
    private var advanceWhenReady = false
    private var prefetchSeries: String? = null
    private var prefetchReadyAt = 0L
    private var clip: TrialClip? = null
    private var playbackReady = false
    private var buffering = false
    private var bufferingSince = 0L
    private var bufferingPosition = 0L
    private var bufferRecoveryKey: String? = null
    private var bufferRecoveryAttempts = 0
    private var restoredSeries: String? = null
    private var lastCheckpoint = 0L
    private var lastSaved: Triple<String, Long, Boolean>? = null
    private var closed = false
    private var active = false
    private var manuallyStarted: String? = null
    private var loading = false
    private var failed = false
    // 空响应多为服务端持续窗口，缩短慢段退避，避免"一直重试"的观感；正式播放页仍用完整退避。
    private val trialRecoveryBudget = RecoveryBudget(
        listOf(2000L, 5000L), listOf(15000L, 20000L, 25000L))
    private var trialPreparationUntil = 0L
    private var trialRecovery: Runnable? = null
    private val exhaustedCursors = mutableSetOf<RecommendationCursor>()
    private var lastPlaybackError: androidx.media3.common.PlaybackException? = null
    private var healTarget: String? = null
    private var healAttempts = 0
    private val stage = object : FrameLayout(activity), HandlesHorizontalKeys {}
    private val hero = FrameLayout(activity)
    private val poster = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
    private var slideDirection = 1
    private val positionLabel = label("正在发现好剧", 12f, TvStyle.muted)
    private lateinit var relatedButton: TextView
    private var describedId: String? = null
    private val relatedCounts = linkedMapOf<String, Pair<Int, Int>>()
    private var relatedProbe: Runnable? = null
    private var relatedScope: RequestScope? = null
    private var relatedJob: java.util.concurrent.Future<*>? = null
    // 右侧互动栏：只展示可真实获取的数据（评论数、收藏状态），不编造点赞/收藏总数。
    private val commentCounts = linkedMapOf<String, Long>()
    private var socialScope: RequestScope? = null
    private var socialJob: java.util.concurrent.Future<*>? = null
    private lateinit var commentCountView: TextView
    private lateinit var favoriteCountView: TextView

    private fun probeSocial(series: Series?) {
        socialScope?.cancel()
        socialJob?.cancel(true)
        if (series == null || commentCounts.containsKey(series.id)) {
            paintSocial()
            return
        }
        val requestScope = RequestScope()
        socialScope = requestScope
        val episodeId = clip?.episodeId.orEmpty()
        socialJob = socialWorker.submit {
            val count = runCatching { requestScope.run { socialStats(series, episodeId) } }.getOrNull()
            main.post {
                if (!closed && active && state.current?.id == series.id && socialScope === requestScope && count != null) {
                    commentCounts[series.id] = count
                    while (commentCounts.size > 24) commentCounts.remove(commentCounts.keys.first())
                    paintSocial()
                }
            }
        }
    }

    private fun probeRelated(series: Series?) {
        relatedProbe?.let(main::removeCallbacks)
        relatedScope?.cancel()
        relatedJob?.cancel(true)
        if (series == null || relatedCounts.containsKey(series.id)) return
        relatedProbe = Runnable {
            if (closed || !active || state.current?.id != series.id) return@Runnable
            val requestScope = RequestScope()
            relatedScope = requestScope
            relatedJob = worker.submit {
                val rows = runCatching { requestScope.run { backend.related(series) } }.getOrNull()
                main.post {
                    if (!closed && active && state.current?.id == series.id && relatedScope === requestScope && rows != null) {
                        relatedCounts[series.id] = rows.size to rows.count { it.upcoming }
                        while (relatedCounts.size > 12) relatedCounts.remove(relatedCounts.keys.first())
                        paintActions()
                    }
                }
            }
        }.also { main.postDelayed(it, 1000) }
    }
    private val title = label("发现下一部好剧", 25f)
    private val meta = label("随心看", 13f, TvVisual.accent)
    // 桌面版推荐页在画面右上显示「当前集/总集」，纯展示不占焦点。
    private val episodeBadge = label("", 15f, Color.WHITE).apply {
        tag = "episode:badge"
        gravity = Gravity.CENTER
        isFocusable = false
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = TvStyle.shape(activity, Color.argb(170, 8, 11, 16), radius = 12)
    }
    private val intro = label("左右换剧，当前作品自动连播。", 15f, TvStyle.muted)
    private val status = label("正在准备热门作品…", 15f)
    private val tag = label("热门发现", 12f, TvVisual.accent)
    private val info = LinearLayout(activity).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    private val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val buttons = mutableListOf<TextView>()
    private val watchButton: TextView
    private lateinit var favoriteButton: TextView
    private val recommendButton: TextView
    private val comicButton: TextView
    private val realButton: TextView

    // 首页播放控制条：覆盖在视频底部的紧凑控制区，由菜单键唤出，不改变既有的
    // 上下左右/确定键语义，保证已经验证过的刷剧手感不受影响。
    private val controls = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = TvStyle.shape(activity, Color.argb(205, 8, 11, 16), radius = 6)
    }
    private val timeLabel = label("00:00 / 00:00", 13f, TvStyle.text).apply { isFocusable = false }
    private val progressView = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isFocusable = false
        max = 1000
        progress = 0
    }
    private lateinit var speedButton: TextView
    private lateinit var qualityButton: TextView
    private lateinit var episodeButton: TextView
    private val controlButtons = mutableListOf<TextView>()
    private var controlsVisible = false
    private var currentSpeed: Float = 1f
    private var currentQuality: Int = 1080

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int = TvStyle.text) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private fun lp(w: Int = -1, h: Int = -2) = LayoutParams(w, h)

    private fun socialIcon(glyph: String, captionView: TextView): TextView =
        label(glyph, 20f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            isFocusable = false
            setPadding(0, dp(6), 0, 0)
            captionView.gravity = Gravity.CENTER
            captionView.setTextColor(Color.WHITE)
            captionView.textSize = 12f
            captionView.isFocusable = false
            captionView.setPadding(0, dp(2), 0, dp(10))
        }

    private fun buildSocialRail() {
        val rail =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = false
                background = TvStyle.shape(activity, Color.argb(150, 8, 11, 16), radius = 20)
                setPadding(dp(10), dp(10), dp(10), dp(6))
            }
        commentCountView = label("…", 12f, Color.WHITE).apply { tag = "social:comments" }
        favoriteCountView = label("…", 12f, Color.WHITE).apply { tag = "social:favorite" }
        rail.addView(socialIcon("评", commentCountView))
        rail.addView(commentCountView)
        rail.addView(socialIcon("藏", favoriteCountView))
        rail.addView(favoriteCountView)
        stage.addView(
            rail,
            FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(12)
            },
        )
        paintSocial()
    }

    private fun paintSocial() {
        if (!::commentCountView.isInitialized) return
        val series = state.current
        commentCountView.text =
            series?.id?.let { commentCounts[it] }?.let { formatCount(it) } ?: ""
        favoriteCountView.text = series?.id?.let { favoriteLabel(it) } ?: ""
    }

    private fun formatCount(value: Long): String =
        when {
            value >= 10000 -> "%.1f万".format(value / 10000.0)
            else -> value.toString()
        }

    /** 桌面版推荐页顶部分类栏：覆盖在画面上的胶囊标签，而非独立占一行的按钮。 */
    private fun tabButton(value: String, action: () -> Unit) =
        label(value, 15f).apply {
            id = View.generateViewId()
            tag = "feed:tab"
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnFocusChangeListener { _, _ -> paintTab(this) }
            setOnClickListener { action() }
        }

    private fun paintTab(tab: TextView) {
        val selected = tab.isSelected
        tab.background =
            TvStyle.shape(
                activity,
                if (selected || tab.hasFocus()) Color.argb(190, 8, 11, 16) else Color.argb(105, 8, 11, 16),
                radius = 18,
            )
        tab.setTextColor(if (selected) Color.WHITE else TvStyle.muted)
        tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun button(value: String, action: () -> Unit) =
        label(value, 14f).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            minHeight = dp(44)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = TvStyle.card(activity, false)
            setOnFocusChangeListener { _, _ -> paintButton(this) }
            setOnClickListener { action() }
        }

    private fun paintButton(button: TextView) {
        button.background = TvStyle.card(activity, button.hasFocus(), button.isSelected)
        button.setTextColor(if (button.isSelected) TvVisual.accent else TvStyle.text)
        button.setTypeface(null, if (button.isSelected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun controlButton(value: String, action: () -> Unit) =
        label(value, 13f).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(40)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = TvStyle.card(activity, false)
            setOnFocusChangeListener { _, _ -> paintButton(this) }
            setOnClickListener { action() }
        }

    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** 仅更新进度条与时间文案；播放控制界面的显隐由菜单键驱动。 */
    private fun paintPlaybackProgress() {
        if (closed) return
        val duration = playback.duration()
        val position = playback.position().coerceAtLeast(0)
        timeLabel.text = "${formatTime(position)} / ${formatTime(duration)}"
        progressView.progress = if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
    }

    private fun paintControls() {
        speedButton.text = "倍速 ${PlaybackSpeed.label(currentSpeed)}"
        qualityButton.text = "清晰度 ${currentQuality}P"
        val episodes = clip?.episodeCount ?: 0
        episodeButton.text = if (episodes > 0) "选集 ${state.episodeIndex + 1}/$episodes" else "选集"
        episodeButton.isEnabled = state.current != null && episodes > 0
        paintPlaybackProgress()
    }

    /** 菜单键在画面与控制条之间切换，双向都保留焦点落点。 */
    private fun toggleControls() {
        if (state.current == null) return
        controlsVisible = !controlsVisible
        controls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        if (controlsVisible) {
            paintControls()
            speedButton.requestFocus()
        } else {
            stage.requestFocus()
        }
        paintActions()
    }

    private fun closeControls() {
        if (!controlsVisible) return
        controlsVisible = false
        controls.visibility = View.GONE
        if (stage.isAttachedToWindow) stage.requestFocus()
    }

    private fun cycleSpeed() {
        val options = PlaybackSpeed.options
        val next = options[(options.indexOf(currentSpeed) + 1) % options.size]
        currentSpeed = next
        playback.setSpeed(next)
        onSpeedChange(next)
        paintControls()
        Toast.makeText(activity, "首页倍速 ${PlaybackSpeed.label(next)}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 清晰度改变需要重新取流，否则当前视频仍沿用旧清晰度。
     * 保留当前集与播放位置，重新加载当前作品的首集/正在播放集。
     */
    private fun cycleQuality() {
        val options = PlaybackQuality.options
        val next = options[(options.indexOf(currentQuality) + 1) % options.size]
        if (next == currentQuality) return
        currentQuality = next
        backend.setQuality(next)
        onQualityChange(next)
        paintControls()
        val position = playback.position().coerceAtLeast(0)
        state.position = position
        remember(force = true)
        clearCurrent()
        clearCache()
        loadCurrent("正在切换清晰度 · ${next}P")
        Toast.makeText(activity, "已切换清晰度 ${next}P，正在重新加载", Toast.LENGTH_SHORT).show()
    }

    private fun openEpisodePicker() {
        val series = state.current ?: return
        state.userPaused = true
        playback.pause()
        remember(force = true)
        closeControls()
        openEpisodes(series)
    }

    // 组装首页：对齐桌面版 recommend.png 的覆盖式推荐页。
    // 左侧导航由 CatalogShell 提供；本组件只负责全屏视频舞台与画面覆盖层。
    init {
        orientation = VERTICAL
        stage.id = View.generateViewId()
        val types = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        recommendButton =
            tabButton("推荐") {
                if (!state.recommendation) {
                    clearCurrent()
                    clearCache()
                    cancelCatalog()
                    state.recommend()
                    describe()
                    if (state.current != null) loadCurrent() else loadPage(false)
                }
            }
        comicButton = tabButton("漫剧") { changeType(DramaCategory.COMIC) }
        realButton = tabButton("真人剧") { changeType(DramaCategory.REAL) }
        val refresh =
            tabButton("换一批") {
                clearCurrent()
                clearCache()
                val restart = exhaustedCursors.remove(state.recommendationCursor)
                cancelCatalog()
                state.refresh(restartCurrentSource = restart)
                describe()
                loadPage(false, resetSource = restart)
            }
        val typeChoices = listOf(recommendButton, comicButton, realButton, refresh)
        typeChoices.forEachIndexed { i, b ->
            types.addView(b, lp(-2, dp(38)).apply { if (i > 0) leftMargin = dp(10) })
            b.nextFocusUpId = b.id
            b.nextFocusDownId = stage.id
            b.nextFocusLeftId = typeChoices.getOrNull(i - 1)?.id ?: b.id
            b.nextFocusRightId = typeChoices.getOrNull(i + 1)?.id ?: b.id
        }

        val carousel = FrameLayout(activity).apply { clipChildren = false; clipToPadding = false }
        hero.background = null
        hero.clipToOutline = false
        hero.elevation = 0f
        val body = FrameLayout(activity)
        hero.addView(body, FrameLayout.LayoutParams(-1, -1))
        tag.setPadding(0, dp(4), 0, dp(4))

        stage.isFocusable = true
        stage.isFocusableInTouchMode = true
        stage.isClickable = true
        stage.contentDescription = "左右换剧，确定暂停或继续，下键进入操作"
        stage.setBackgroundColor(Color.BLACK)
        stage.addView(playback.view, FrameLayout.LayoutParams(-1, -1))
        stage.addView(poster, FrameLayout.LayoutParams(-1, -1))
        // 分类栏与集数角标覆盖在画面顶部。
        stage.addView(
            types,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(16)
            },
        )
        stage.addView(
            episodeBadge,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(18); rightMargin = dp(20)
            },
        )
        // 右侧互动栏：仅展示真实可得的评论数与收藏状态，纯展示不占焦点。
        buildSocialRail()
        // 播放控制：时间 + 进度 + 倍速/清晰度/选集，覆盖在画面底部。
        speedButton = controlButton("倍速") { cycleSpeed() }
        qualityButton = controlButton("清晰度") { cycleQuality() }
        episodeButton = controlButton("选集") { openEpisodePicker() }
        controlButtons += listOf(speedButton, qualityButton, episodeButton)
        timeLabel.minWidth = dp(96)
        controls.addView(timeLabel, lp(-2, dp(44)))
        controls.addView(progressView, lp(0, dp(12)).apply { weight = 1f; leftMargin = dp(10); rightMargin = dp(10) })
        controlButtons.forEachIndexed { index, b ->
            controls.addView(b, lp(-2, dp(44)).apply { if (index > 0) leftMargin = dp(8) })
            b.nextFocusLeftId = controlButtons.getOrNull(index - 1)?.id ?: b.id
            b.nextFocusRightId = controlButtons.getOrNull(index + 1)?.id ?: b.id
            b.nextFocusUpId = b.id
            b.nextFocusDownId = b.id
        }
        stage.addView(
            controls,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
        )
        currentSpeed = PlaybackSpeed.normalize(initialSpeed)
        currentQuality = PlaybackQuality.normalize(initialQuality)

        // 作品信息覆盖在画面左下，取代原来的右侧独立信息栏。
        tag.setTextColor(TvVisual.accent)
        tag.textSize = 12f
        info.addView(tag)
        title.typeface = Typeface.DEFAULT_BOLD
        title.textSize = 21f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        info.addView(title)
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        meta.setTextColor(TvVisual.accent)
        meta.setPadding(0, dp(3), 0, dp(3))
        info.addView(meta)
        intro.maxLines = 2
        intro.textSize = 13f
        intro.ellipsize = TextUtils.TruncateAt.END
        info.addView(intro)
        positionLabel.gravity = Gravity.START
        positionLabel.textSize = 11f
        positionLabel.setTextColor(TvStyle.muted)
        info.addView(positionLabel)
        status.gravity = Gravity.START
        status.textSize = 12f
        status.setPadding(dp(8), dp(5), dp(8), dp(5))
        status.background = TvStyle.shape(activity, Color.argb(215, 11, 15, 21))
        info.addView(status)
        info.isFocusable = false
        info.setPadding(dp(14), dp(12), dp(14), dp(12))
        info.background = TvStyle.shape(activity, Color.argb(150, 8, 11, 16), radius = 12)
        stage.addView(
            info,
            FrameLayout.LayoutParams(dp(470), -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(22); bottomMargin = dp(96)
            },
        )

        body.addView(stage, FrameLayout.LayoutParams(-1, -1))
        carousel.addView(hero, FrameLayout.LayoutParams(-1, -1))
        addView(carousel, lp(-1, 0).apply { weight = 1f })
        watchButton =
            button("播放全集") { openCurrent() }
        favoriteButton =
            button("账户收藏") {
                state.current?.let {
                    state.userPaused = true
                    playback.pause()
                    remember(force = true)
                    paintActions()
                    favorite(it, favoriteButton)
                }
            }
        relatedButton = button("相关剧集 / 预约") {
            state.current?.let {
                state.userPaused = true
                playback.pause()
                remember(force = true)
                paintActions()
                related(it, relatedButton)
            }
        }
        buttons += listOf(watchButton, favoriteButton, relatedButton)
        buttons.forEachIndexed { index, b ->
            actions.addView(
                b,
                lp(0, dp(44)).apply {
                    weight = 1f
                    if (index < buttons.lastIndex) rightMargin = dp(10)
                },
            )
            b.nextFocusLeftId = buttons.getOrNull(index - 1)?.id ?: b.id
            b.nextFocusRightId = buttons.getOrNull(index + 1)?.id ?: b.id
            b.nextFocusUpId = stage.id
            b.nextFocusDownId = b.id
        }
        // 操作栏覆盖在画面底部、默认隐藏；下键唤出，返回键退回画面并收起。
        actions.visibility = View.GONE
        actions.setPadding(dp(12), dp(10), dp(12), dp(10))
        actions.background = TvStyle.shape(activity, Color.argb(210, 8, 11, 16), radius = 12)
        stage.addView(
            actions,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                bottomMargin = dp(96); leftMargin = dp(22); rightMargin = dp(22)
            },
        )
        stage.nextFocusLeftId = stage.id
        stage.nextFocusRightId = stage.id
        stage.nextFocusUpId = recommendButton.id
        stage.nextFocusDownId = watchButton.id
        nav.forEach {
            it.nextFocusDownId =
                (if (state.recommendation) recommendButton
                    else if (state.category == DramaCategory.COMIC) comicButton else realButton)
                    .id
        }
        stage.setOnClickListener { toggle() }
        hero.setOnClickListener { toggle() }
        stage.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) recommendButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) showActions()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                        move(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) toggle()
                    true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_DOWN) toggleControls()
                    true
                }
                else -> false
            }
        }
        // 控制条内：左/右在按钮间移动（焦点链已设置），返回键回到画面。
        controlButtons.forEach { b ->
            b.setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU)
                ) {
                    closeControls()
                    true
                } else {
                    false
                }
            }
        }
        playback.onError = { error -> lastPlaybackError = error }
        playback.status = { value ->
            if (!closed && clip != null)
                when (value) {
                    TrialStatus.BUFFERING -> {
                        if (!buffering) {
                            bufferingSince = android.os.SystemClock.elapsedRealtime()
                            bufferingPosition = playback.position()
                        }
                        buffering = true
                        // Give the current episode both the connection and decoder budgets.
                        cancelPrefetch()
                        playback.suspendPrefetch()
                        status.visibility = View.VISIBLE
                        status.text = "正在加载当前集…"
                    }
                    TrialStatus.READY -> {
                        buffering = false
                        bufferingSince = 0L
                        playbackReady = true
                        poster.visibility = View.GONE
                        status.visibility = if (state.userPaused) View.VISIBLE else View.GONE
                        failed = false
                        status.text = if (state.userPaused) "播放已暂停" else "随心看 · 喜欢就看全集"
                        paintActions()
                        prefetchUpcoming()
                    }
                    TrialStatus.ENDED -> finishEpisode()
                    TrialStatus.ERROR -> {
                        buffering = false
                        poster.visibility = View.VISIBLE
                        playback.pause()
                        if (!selfHealTrial()) {
                            failed = true
                            status.visibility = View.VISIBLE
                            status.text = "播放暂不可用 · 可重试、换剧或查看全集"
                        }
                        paintActions()
                    }
                }
        }
        describe()
        paintActions()
    }

    // 设置进入首页时的初始焦点，并在应用位于前台时启动播放流程。
    fun start(foreground: Boolean, focusNav: Boolean) {
        if (focusNav) nav[0].requestFocus() else stage.requestFocus()
        if (foreground) resume()
    }

    private val prefetchPoll =
        object : Runnable {
            override fun run() {
                if (!active || closed) return
                val now = android.os.SystemClock.elapsedRealtime()
                checkBuffering(now)
                ensureNextWorks()
                prefetchUpcoming()
                if (controlsVisible) paintPlaybackProgress()
                if (now - lastCheckpoint >= 5000) { remember(); lastCheckpoint = now }
                main.postDelayed(this, 250)
            }
        }

    /** A buffering event has no built-in terminal callback when the loader stops making progress. */
    private fun checkBuffering(now: Long) {
        val current = clip ?: return
        if (!buffering || !active || closed || loading || failed || state.ended || state.userPaused) {
            bufferingSince = 0L
            return
        }
        val position = playback.position().coerceAtLeast(0)
        if (bufferingSince == 0L || position != bufferingPosition) {
            bufferingSince = now
            bufferingPosition = position
            return
        }
        if (now - bufferingSince < 15_000) return
        val key = current.detail.series.id + ":" + current.episodeId
        if (bufferRecoveryKey != key) {
            bufferRecoveryKey = key
            bufferRecoveryAttempts = 0
        }
        buffering = false
        cancelPrefetch()
        playback.suspendPrefetch()
        if (bufferRecoveryAttempts >= 2) {
            clearCurrent()
            clearCache()
            failed = true
            poster.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            status.text = "加载超时 · 按确定重试，或左右换剧"
            paintActions()
            return
        }
        bufferRecoveryAttempts++
        // Refresh the URL and reader, keeping the episode and its last playback position.
        // Do not reuse a preloaded source which may share the stalled request.
        state.position = position
        state.episodeIndex = current.trialEpisodeIndex - 1
        clearCache()
        loadCurrent("加载停滞，正在恢复播放 · $bufferRecoveryAttempts/2")
    }

    fun resume() {
        if (closed || active) return
        active = true
        probeRelated(state.current)
        main.removeCallbacks(prefetchPoll)
        main.postDelayed(prefetchPoll, 250)
        if (clip != null) {
            if (autoNext && state.ended && state.episodeIndex + 1 < clip!!.episodeCount) {
                state.ended = false
                finishEpisode()
                return
            }
            if (!state.userPaused && !state.ended) playback.resume()
            paintActions()
            prefetchUpcoming()
        } else if (state.current != null) loadCurrent() else loadPage(false)
    }

    fun pause() {
        if (closed) return
        remember(force = true)
        active = false
        bufferingSince = 0L
        playback.suspendPrefetch()
        cancelCatalog()
        main.removeCallbacks(prefetchPoll)
        relatedProbe?.let(main::removeCallbacks)
        relatedScope?.cancel()
        relatedJob?.cancel(true)
        playback.pause()
        cancelRequest()
        cancelPrefetch()
        paintActions()
    }

    fun back(): Boolean {
        if (!actions.hasFocus()) return false
        actions.visibility = View.GONE
        info.visibility = View.VISIBLE
        // 与 close() 保持一致：info 恢复可见时同步恢复标签，否则「推荐 · 随心看」会残留隐藏。
        tag.visibility = info.visibility
        stage.requestFocus()
        return true
    }

    /** 操作栏是否持有焦点，供返回键决策「先退回画面」还是「直接退出」。 */
    fun actionsHasFocus(): Boolean = actions.hasFocus()

    fun refreshAccountLabel() {
        if (!closed) {
            paintActions()
            paintSocial()
        }
    }

    fun currentPlayback(seriesId: String): TrialClip? {
        if (!playbackReady || failed || buffering || state.userPaused || state.ended || playback.position() <= 0) return null
        return clip?.takeIf { it.detail.series.id == seriesId }
    }

    private fun openCurrent() {
        state.current?.let { remember(force = true); pause(); watch(it) }
    }

    // 下键将焦点移到覆盖式操作栏；操作栏显示时收起作品信息，避免遮挡。
    private fun showActions() {
        actions.visibility = View.VISIBLE
        paintActions()
        info.visibility = View.GONE
        (if (watchButton.isEnabled) watchButton else stage).requestFocus()
    }

    private fun paintActions() {
        val primaryAction =
            when {
                trialRecovery != null -> "立即重试播放"
                loading -> "正在准备播放…"
                failed || state.current == null -> "重试播放"
                state.ended -> "重新播放"
                state.userPaused -> "继续播放"
                else -> "暂停播放"
            }
        stage.contentDescription = "左右换剧，下键进入操作，确定$primaryAction"
        watchButton.isEnabled = state.current != null
        favoriteButton.isEnabled = state.current != null
        val relatedCount = state.current?.id?.let { relatedCounts[it] }
        relatedButton.isEnabled = state.current != null && relatedCount?.first != 0
        relatedButton.text = when {
            relatedCount == null -> "相关剧集 / 预约"
            relatedCount.first == 0 -> "暂无相关剧集"
            relatedCount.second > 0 -> "相关 ${relatedCount.first} 部 · 可预约 ${relatedCount.second} 部"
            else -> "相关剧集 ${relatedCount.first} 部"
        }
        if (!relatedButton.isEnabled && relatedButton.hasFocus()) watchButton.requestFocus()
        favoriteButton.text = state.current?.let { favoriteLabel(it.id) } ?: "账户收藏"
        recommendButton.isSelected = state.recommendation
        comicButton.isSelected = !state.recommendation && state.category == DramaCategory.COMIC
        realButton.isSelected = !state.recommendation && state.category == DramaCategory.REAL
        paintTab(recommendButton)
        paintTab(comicButton)
        paintTab(realButton)
        // 覆盖式布局下作品信息与操作栏互斥，避免两处覆盖层相互遮挡。
        info.visibility = if (actions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        tag.visibility = info.visibility
        stage.keepScreenOn = active && clip != null && !state.userPaused && !state.ended && !failed
    }

    private fun describe() {
        val series = state.current
        if (describedId != series?.id) {
            describedId = series?.id
            probeRelated(series)
            poster.setImageDrawable(null)
            poster.visibility = View.VISIBLE
            series?.cover?.takeIf(String::isNotBlank)?.let { loadCover(poster, it) }
            hero.animate().cancel()
            hero.alpha = .75f
            hero.translationX = dp(24).toFloat() * slideDirection
            hero.animate().alpha(1f).translationX(0f).setDuration(240).start()
        }
        ensureNextWorks()
        positionLabel.text = if (series == null) "正在发现好剧" else "第 ${state.index + 1} 部 · 左右换剧 · ${if (autoNext) "自动连播下一集" else "播完本集停止"}"
        title.text = series?.title ?: "发现下一部好剧"
        // 换剧瞬间总数未知，先清空角标，待 attach 拿到真实集数再填充，避免显示假数字。
        if (series?.id != clip?.detail?.series?.id) episodeBadge.text = ""
        meta.text =
            if (series == null) "随心看"
            else
                listOf("第 ${state.episodeIndex + 1} 集", series.badge, series.tags)
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
        intro.text =
            series?.description?.ifBlank { "当前剧集自动连续播放，左右切换其他作品。" } ?: "选择漫剧或真人剧，左右换一部，找到想追的故事。"
        tag.text = if (state.recommendation) "推荐 · 随心看" else "${state.category.label}推荐 · 随心看"
        paintActions()
        if (controlsVisible) paintControls()
    }

    private fun remember(completed: Boolean = false, force: Boolean = false) {
        val current = clip ?: return
        state.position = playback.position()
        if (!playbackReady) return
        if (state.position <= 0 && !completed) return
        val stamp = Triple(current.episodeId, state.position, completed || state.ended)
        if (stamp == lastSaved && !force) return
        saveProgress(WatchProgress(current.detail.series, current.episodeId,
            current.trialEpisodeIndex - 1, state.position, playback.duration(),
            stamp.third, System.currentTimeMillis()), current.episodeCount, force || completed)
        lastSaved = stamp
    }

    private var pendingTrial: Runnable? = null

    private fun cancelRequest() {
        pendingTrial?.let(main::removeCallbacks)
        pendingTrial = null
        trialRecovery?.let(main::removeCallbacks)
        trialRecovery = null
        generation++
        scope?.cancel()
        scope = null
        job?.cancel(true)
        job = null
        loading = false
    }

    private fun cancelPrefetch() {
        prefetchGeneration++
        prefetchScope?.cancel()
        prefetchScope = null
        prefetchJob?.cancel(true)
        prefetchJob = null
        prefetchTarget = null
        attemptedPrefetch.clear()
        prefetchFailures.clear()
        nextEpisode?.close()
        nextEpisode = null
        nextEpisodeAttempts = 0
        nextEpisodeRetryAt = 0
        prefetchSeries = null
        prefetchReadyAt = 0
    }

    private fun closeCached(cached: CachedTrial) {
        playback.remove(cached.clip.detail.series.id)
        cached.clip.close()
    }

    private fun clearCache() {
        cachedTrials.values.forEach(::closeCached)
        cachedTrials.clear()
    }

    private fun clearCurrent(
        retain: Boolean = false,
        keepPlayer: Boolean = false,
        keepPrefetch: Boolean = false,
    ) {
        remember(force = true)
        buffering = false
        bufferingSince = 0L
        cancelRequest()
        if (!keepPrefetch) cancelPrefetch()
        if (!keepPlayer) {
            playback.clear()
            heldEpisode?.close()
            heldEpisode = null
        }
        clip?.let {
            if (keepPlayer && !retain) {
                heldEpisode?.close()
                heldEpisode = it
            } else if (retain && !failed && it.trialEpisodeIndex == 1) {
                cachedTrials.put(it.detail.series.id, CachedTrial(it))?.let(::closeCached)
            } else it.close()
        }
        clip = null
        playbackReady = false
    }

    private fun takeCached(id: String): TrialClip? {
        val cached = cachedTrials.remove(id) ?: return null
        if (android.os.SystemClock.elapsedRealtime() - cached.savedAt > 90_000) {
            closeCached(cached)
            return null
        }
        return cached.clip
    }

    // 播放自愈：解码类失败写入设备能力经验表并自动换低档重载（每剧至多 3 轮，且仅在学到
    // 新失败键时继续，同一键反复重载没有意义）。重载复用 loadCurrent（缓存/预取优先），与
    // 手动重试成本相同。bytevc2 锁定集学完 1080P 键后，重载即落入确定性报错，不无限循环。
    private fun selfHealTrial(): Boolean {
        val series = state.current ?: return false
        val track = clip?.track
        if (healTarget != series.id) {
            healTarget = series.id
            healAttempts = 0
        }
        if (
            !shouldSelfHealTrial(
                lastPlaybackError?.errorCode,
                track != null,
                healAttempts,
            )
        )
            return false
        val knownBefore = CodecCapabilities.learnedCount()
        CodecCapabilities.learnFailure(track!!.fourcc, track.width, track.height)
        if (CodecCapabilities.learnedCount() == knownBefore) return false
        healAttempts += 1
        loadCurrent("设备不支持该清晰度，正在自动切换…")
        return true
    }

    // 当前作品两次快速恢复后，仅在主备播放接口都为空时使用有界退避。
    // 切换、暂停和关闭通过 cancelRequest 取消本集的恢复。
    private fun recoverEmptyTrial(problem: Throwable): Boolean {
        if (generateSequence(problem) { it.cause }.take(8).none { it is EmptyPlaybackResponseException }) return false
        val bothModelsEmpty = hasEmptyPlaybackModels(problem)
        val delay = trialRecoveryBudget.delayMillis ?: return false
        if (!bothModelsEmpty && (trialRecoveryBudget.slow ||
                trialPreparationUntil - android.os.SystemClock.elapsedRealtime() <= delay)) return false
        val target = state.current?.id ?: return false
        val token = generation
        trialRecoveryBudget.consume()
        val attempt = trialRecoveryBudget.attempts
        val total = if (bothModelsEmpty) trialRecoveryBudget.total else trialRecoveryBudget.fastAttempts
        val deadline = android.os.SystemClock.elapsedRealtime() + delay
        loading = true
        failed = false
        val retry = object : Runnable {
            override fun run() {
                if (closed || !active || token != generation || state.current?.id != target) return
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    trialRecovery = null
                    if (bothModelsEmpty) trialPreparationUntil = android.os.SystemClock.elapsedRealtime() + 20_000
                    loadCurrent("正在重新获取播放数据 · $attempt/$total", recovering = true)
                } else {
                    status.visibility = View.VISIBLE
                    status.text = "播放源暂无数据 · ${(remaining + 999) / 1000} 秒后重试 $attempt/$total"
                    main.postDelayed(this, minOf(1000L, remaining))
                }
            }
        }
        trialRecovery = retry
        retry.run()
        paintActions()
        return true
    }

    // 后台发起请求，主线程更新界面；页面关闭、切换或暂停后丢弃过期结果。
    private fun <T> request(
        action: () -> T,
        discard: (T) -> Unit = {},
        recoverTrial: Boolean = false,
        done: (T) -> Unit,
    ) {
        cancelRequest()
        loading = true
        val token = generation
        val requestedCursor = state.recommendationCursor
        val requestScope = RequestScope()
        scope = requestScope
        job =
            worker.submit {
                try {
                    val result = requestScope.run {
                        if (!recoverTrial) action() else {
                            val remaining = trialPreparationUntil - android.os.SystemClock.elapsedRealtime()
                            if (remaining <= 0) throw java.net.SocketTimeoutException("播放准备超时")
                            RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remaining), action)
                        }
                    }
                    main.post {
                        if (closed || token != generation || !active) discard(result)
                        else {
                            loading = false
                            done(result)
                        }
                    }
                } catch (error: Exception) {
                    main.post {
                        if (closed || token != generation || !active) return@post
                        loading = false
                        if (recoverTrial && recoverEmptyTrial(error)) return@post
                        failed = true
                        status.visibility = View.VISIBLE
                        val message =
                            error.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(90)
                        if (error is NoNewRecommendationsException) {
                            exhaustedCursors += requestedCursor
                            status.text = "推荐接口未返回新作品 · 最近已去重 ${error.rememberedCount} 部\n可稍后换一批，或切换漫剧、真人剧继续发现"
                        } else if (recoverTrial && trialRecoveryBudget.attempts > 0) {
                            diagnosticDetail(error)?.take(640)?.let {
                                android.util.Log.i("HongguoTV", "Home playback recovery exhausted: $it")
                            }
                            status.text = "播放源暂不可用 · 可按确定重试，或按 ← → 换一部"
                        } else status.text = "暂时无法加载 · ${message.ifBlank { "请重试或换一部" }}"
                        paintActions()
                    }
                }
            }
    }

    // 按当前推荐模式请求下一页；结果写入状态后，再加载当前作品的播放资源。
    private fun loadPage(advance: Boolean, resetSource: Boolean = false) {
        if (!active || closed || loading) return
        if (state.page >= 100 || (!state.hasMore && state.current != null)) {
            status.visibility = View.VISIBLE
            status.text = "本批作品已看完，可切换类型继续发现"
            return
        }
        status.visibility = View.VISIBLE
        status.text = "正在读取热门作品…"
        val page = state.page + 1
        val category = state.category
        val recommendation = state.recommendation
        val cursor = state.recommendationCursor
        request({
            if (resetSource) backend.resetRecommendations(if (recommendation) null else category)
            if (recommendation) backend.recommendations(page, cursor)
            else backend.categoryRecommendations(category, page, cursor)
        }) { result ->
            state.append(result, page)
            if (advance) state.move(1)
            if (state.current == null) {
                status.text = "暂时没有可播放的作品，可切换类型或重试"
                failed = true
                paintActions()
            } else loadCurrent()
        }
    }

    // 优先复用缓存或正在进行的预加载，避免为同一部作品重复请求。
    private fun loadCurrent(statusHint: String? = null, recovering: Boolean = false, settle: Boolean = false, continuingEpisode: Boolean = false) {
        val series = state.current ?: return
        if (restoreWatchProgress && restoredSeries != series.id) {
            restoredSeries = series.id
            restoreProgress(series.id)?.takeIf { !it.completed }?.let {
                state.episodeIndex = it.episodeIndex
                state.position = it.position
            }
        }
        val episodeIndex = state.episodeIndex
        if (!active || closed) return
        if (!recovering) {
            trialRecoveryBudget.reset()
            trialPreparationUntil = android.os.SystemClock.elapsedRealtime() + 20_000
        }
        // 记下展示过的作品：冷启动时用它跳过重复内容，而不是每次都从同一部开始。
        noteDisplayed(series)
        clearCurrent(keepPlayer = continuingEpisode, keepPrefetch = prefetchTarget == series.id)
        failed = false
        describe()
        status.visibility = View.VISIBLE
        status.text = statusHint ?: "正在准备随心看…"
        trimCache()
        val cached = if (state.episodeIndex == 0) takeCached(series.id) else null
        if (cached != null) attach(cached)
        else if (state.episodeIndex == 0 && prefetchTarget == series.id && prefetchJob != null) {
            // 将正在进行的预加载转为当前播放，不取消后再重复请求地址。
            loading = true
            android.util.Log.i("HongguoTV", "Home promoted in-flight preload")
        } else if (settle) {
            // 快速连刷只更新卡片，停留三百毫秒后才发起未缓存作品的播放准备。
            loading = true
            val token = generation
            val pending = Runnable {
                pendingTrial = null
                if (!closed && active && token == generation && state.current?.id == series.id)
                    request({ if (episodeIndex == 0) backend.trial(series) else backend.episode(series, episodeIndex) }, { it.close() }, recoverTrial = true, done = ::attach)
            }
            pendingTrial = pending
            main.postDelayed(pending, 300)
        } else request({ if (episodeIndex == 0) backend.trial(series) else backend.episode(series, episodeIndex) }, { it.close() }, recoverTrial = true, done = ::attach)
    }

    private fun attach(next: TrialClip) {
        val key = next.detail.series.id + ":" + next.episodeId
        if (bufferRecoveryKey != key) {
            bufferRecoveryKey = key
            bufferRecoveryAttempts = 0
        }
        if (!autoPreview && manuallyStarted != next.detail.series.id) state.userPaused = true
        playbackReady = false
        clip = next
        state.episodeIndex = next.trialEpisodeIndex - 1
        // clip 已就绪，此时才探测真实评论数（describe 阶段 clip 尚未赋值会取到空集）。
        probeSocial(next.detail.series)
        val series = next.detail.series
        val tags = series.tags.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        meta.text = "第 ${next.trialEpisodeIndex} 集 · 共 ${next.episodeCount} 集$tags"
        episodeBadge.text = "${next.trialEpisodeIndex}/${next.episodeCount} 集"
        if (series.description.isNotBlank()) intro.text = series.description
        val autoplay = active && !state.userPaused && !state.ended
        playback.setCurrentIndex(state.offset + state.index)
        if (!playback.select(next, state.position, autoplay)) {
            playback.load(next, state.position, autoplay)
            cachedTrials.values.forEach { queueCached(it.clip) }
        }
        heldEpisode?.let {
            playback.removeClip(it)
            it.close()
        }
        heldEpisode = null
        paintActions()
        if (controlsVisible) paintControls()
    }

    private fun finishEpisode() {
        val finished = clip ?: return
        if (state.ended) return
        remember(completed = true)
        state.ended = true
        if (!autoNext || finished.trialEpisodeIndex >= finished.episodeCount) {
            state.userPaused = true
            status.visibility = View.VISIBLE
            status.text = if (finished.trialEpisodeIndex >= finished.episodeCount) "本剧已播完 · 左右换剧，或确定重播本集" else "本集已播完 · 已关闭自动下一集，可下键选集"
            paintActions()
            return
        }
        // Post outside the player callback; duplicate end notifications cannot advance twice.
        main.post {
            if (closed || !active || clip !== finished || !state.ended) return@post
            val warmed = nextEpisode?.takeIf {
                it.detail.series.id == finished.detail.series.id && it.trialEpisodeIndex == finished.trialEpisodeIndex + 1
            }
            if (warmed != null) nextEpisode = null
            clearCurrent(keepPlayer = true)
            state.episodeIndex = finished.trialEpisodeIndex
            state.position = 0
            state.ended = false
            state.userPaused = false
            if (warmed != null) attach(warmed) else loadCurrent(continuingEpisode = true)
        }
    }

    private fun noteDisplayed(series: Series) =
        backend.noteDisplayed(
            series,
            state.recommendationCursor,
            if (state.recommendation) null else state.category,
        )

    private fun queueCached(clip: TrialClip) {
        val index = state.items.indexOfFirst { it.id == clip.detail.series.id }
        if (index >= 0) playback.queue(clip, state.offset + index)
    }

    private fun trimCache() {
        val allowed =
            state.items
                .subList(
                    (state.index - 1).coerceAtLeast(0),
                    minOf(state.index + 4, state.items.size),
                )
                .map { it.id }
                .toSet()
        val now = android.os.SystemClock.elapsedRealtime()
        val stale =
            cachedTrials
                .filter { it.key !in allowed || now - it.value.savedAt > 90_000 }
                .keys
                .toList()
        stale.forEach { cachedTrials.remove(it)?.let(::closeCached) }
    }

    fun canWarmCatalog() = active && playbackReady && !buffering && !loading && !failed &&
        prefetchJob == null && (state.userPaused || playback.canPrefetch())

    /** 保留前一部与后三部缓存；当前稳定半秒后准备下一部，三秒、六秒后再扩展。 */
    private fun prefetchUpcoming() {
        if (
            closed ||
                !active ||
                clip == null ||
                buffering ||
                failed ||
                loading ||
                prefetchJob != null || backgroundBusy()
        )
            return
        if (!playback.canPrefetch()) {
            prefetchReadyAt = android.os.SystemClock.elapsedRealtime()
            return
        }
        trimCache()
        val now = android.os.SystemClock.elapsedRealtime()
        if (prefetchSeries != state.current?.id) {
            prefetchSeries = state.current?.id
            prefetchReadyAt = now
        }
        if (now - prefetchReadyAt < 500 || backend.shouldDeferPlaybackPrefetch()) return
        val current = clip ?: return
        if (autoNext && current.trialEpisodeIndex < current.episodeCount && nextEpisode == null &&
            playback.duration() > 0 && playback.duration() - playback.position() <= 8_000 &&
            nextEpisodeAttempts < 2 && now >= nextEpisodeRetryAt) {
            prefetchNextEpisode(current)
            return
        }
        val depth = when {
            now - prefetchReadyAt >= 6000 -> 3
            now - prefetchReadyAt >= 3000 -> 2
            else -> 1
        }
        val upcoming = state.items.drop(state.index + 1).take(3)
        val next = upcoming.take(depth).firstOrNull { it.id !in cachedTrials && it.id !in attemptedPrefetch }
        if (next == null) return
        attemptedPrefetch += next.id
        prefetchTarget = next.id
        val token = prefetchGeneration
        val requestScope = RequestScope()
        prefetchScope = requestScope
        prefetchJob =
            worker.submit {
                val prepared =
                    runCatching { requestScope.run {
                        RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                            backend.preload(next)
                        }
                    } }.getOrNull()
                main.post {
                    if (closed || !active || token != prefetchGeneration) prepared?.close()
                    else {
                        prefetchJob = null
                        prefetchScope = null
                        prefetchTarget = null
                        if (state.current?.id == next.id && clip == null) {
                            loading = false
                            if (prepared != null) attach(prepared) else loadCurrent(recovering = true)
                        } else if (prepared != null) {
                            cachedTrials.put(next.id, CachedTrial(prepared))?.let(::closeCached)
                            queueCached(prepared)
                        }
                        if (prepared == null && state.current?.id != next.id) {
                            val failures = (prefetchFailures[next.id] ?: 0) + 1
                            prefetchFailures[next.id] = failures
                            if (failures < 2) main.postDelayed({
                                if (!closed && active && token == prefetchGeneration) attemptedPrefetch.remove(next.id)
                            }, 15_000)
                        }
                    }
                }
            }
    }

    private fun prefetchNextEpisode(current: TrialClip) {
        val token = prefetchGeneration
        val request = RequestScope()
        prefetchScope = request
        nextEpisodeAttempts++
        nextEpisodeRetryAt = android.os.SystemClock.elapsedRealtime() + 20_000
        prefetchJob = worker.submit {
            val prepared = runCatching { request.run {
                RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                    backend.prefetchEpisode(current.detail.series, current.trialEpisodeIndex)
                }
            } }.getOrNull()
            main.post {
                if (closed || !active || token != prefetchGeneration || clip !== current) prepared?.close()
                else {
                    prefetchJob = null
                    prefetchScope = null
                    nextEpisode = prepared
                }
            }
        }
    }

    private fun cancelCatalog() {
        catalogGeneration++
        catalogScope?.cancel()
        catalogScope = null
        catalogJob?.cancel(true)
        catalogJob = null
        advanceWhenReady = false
        catalogRetryAt = 0L
        catalogFailed = false
    }

    /** Metadata has its own lane: a paused or failed video must not empty the carousel. */
    private fun ensureNextWorks(advance: Boolean = false) {
        if (!active || closed || state.current == null) return
        if (advance) { advanceWhenReady = true; catalogRetryAt = 0L }
        if (catalogJob != null || state.items.size - state.index - 1 > 5 ||
            android.os.SystemClock.elapsedRealtime() < catalogRetryAt) return
        val restart = !state.hasMore || state.page >= 100
        if (restart) state.continueBatch()
        val page = state.page + 1
        val cursor = state.recommendationCursor
        val category = state.category
        val recommendation = state.recommendation
        val token = catalogGeneration
        val request = RequestScope()
        catalogScope = request
        catalogFailed = false
        catalogJob = catalogWorker.submit {
            val result = runCatching { request.run {
                if (restart) backend.resetRecommendations(if (recommendation) null else category)
                if (recommendation) backend.recommendations(page, cursor)
                else backend.categoryRecommendations(category, page, cursor)
            } }
            main.post {
                if (closed || !active || token != catalogGeneration || cursor !== state.recommendationCursor) return@post
                catalogJob = null
                catalogScope = null
                val before = state.items.map { it.id }.toSet()
                val batch = result.getOrNull()
                if (batch != null && page == state.page + 1) state.append(batch, page)
                val added = batch?.items?.any { it.id !in before } == true
                if (!added) {
                    catalogFailed = true
                    catalogRetryAt = android.os.SystemClock.elapsedRealtime() + 20_000
                    if (result.exceptionOrNull() is NoNewRecommendationsException) state.continueBatch()
                }
                val advanceNow = advanceWhenReady && state.next != null
                advanceWhenReady = false
                describe()
                if (advanceNow) move(1)
            }
        }
    }

    private fun move(delta: Int) {
        if (closed || !active) return
        if (delta < 0 || state.next != null) advanceWhenReady = false
        slideDirection = delta
        if (delta < 0 && state.index == 0) {
            status.visibility = View.VISIBLE
            status.text = "已到本次浏览的最早作品"
            return
        }
        if (delta > 0 && state.next == null) {
            ensureNextWorks(advance = true)
            status.text = "下一部正在加载…"
            status.visibility = View.VISIBLE
            return
        }
        val target = state.items.getOrNull(state.index + delta) ?: return
        val ready = if (restoreWatchProgress && restoreProgress(target.id)?.let { !it.completed && (it.episodeIndex > 0 || it.position > 0) } == true) null else takeCached(target.id)
        clearCurrent(
            retain = true,
            keepPlayer = ready != null,
            keepPrefetch = target.id == prefetchTarget,
        )
        if (state.move(delta)) {
            if (ready != null) {
                restoredSeries = target.id
                noteDisplayed(target)
                failed = false
                describe()
                status.visibility = View.VISIBLE
                status.text = "正在切换作品…"
                attach(ready)
                trimCache()
            } else loadCurrent(settle = true)
        }
    }

    private fun toggle() {
        if (loading && trialRecovery == null) return
        // 倒计时期间允许主动立即重试；取消旧定时任务，避免随后再次重复读取。
        if (trialRecovery != null) cancelRequest()
        manuallyStarted = state.current?.id
        if (failed || state.current == null || clip == null) {
            bufferRecoveryAttempts = 0
            if (state.current == null) state.refresh()
            state.userPaused = false
            state.ended = false
            if (state.current == null) loadPage(false) else loadCurrent()
            return
        } else if (state.ended) {
            state.position = 0
            state.ended = false
            state.userPaused = false
            playback.restart()
        } else {
            state.userPaused = !state.userPaused
            bufferingSince = 0L
            if (state.userPaused) {
                remember(force = true)
                playback.pause()
            } else if (active) playback.resume()
        }
        status.visibility = if (state.userPaused) View.VISIBLE else View.GONE
        status.text = if (state.userPaused) "播放已暂停" else "随心看 · 喜欢就看全集"
        paintActions()
    }

    private fun changeType(category: DramaCategory) {
        if (!state.recommendation && state.category == category) return
        clearCurrent()
        cancelCatalog()
        state.select(category)
        clearCache()
        describe()
        if (state.current != null) loadCurrent() else loadPage(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelCatalog()
        main.removeCallbacks(prefetchPoll)
        relatedProbe?.let(main::removeCallbacks)
        relatedScope?.cancel()
        relatedJob?.cancel(true)
        socialScope?.cancel()
        socialJob?.cancel(true)
        clearCurrent()
        clearCache()
        active = false
        info.visibility = View.VISIBLE
        tag.visibility = info.visibility
        stage.keepScreenOn = false
        playback.release()
        backend.close()
        worker.shutdownNow()
        socialWorker.shutdownNow()
        catalogWorker.shutdownNow()
    }
}
