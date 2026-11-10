package app.lutapply

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SingleColorLut
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import androidx.media3.common.C
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

// path: bundled asset (null for Raw and for loaded files). cube/file: a user-loaded input LUT kept in filesDir/inputs.
private class InputEntry(
    val name: String,
    val path: String?,
    val cube: FloatCube? = null,
    val id: Int = 0,
    val file: File? = null,
) {
    val isLoaded get() = cube != null
    val key get() = when {
        file != null -> "file:${file.name}"
        path != null -> path
        else -> "raw"
    }
    val isRaw get() = path == null && cube == null
    val isPassthrough get() = path?.contains("passthrough", ignoreCase = true) == true
    val isSlog3 get() = path?.let { it.contains("slog3", true) || it.contains("s-log3", true) } == true
    val isSamsung get() = path?.contains("samsung-log", ignoreCase = true) == true
}

private sealed class LookItem(val name: String, val category: String) {
    // The first two tiles. Rec.709: the camera profile only (log converted to normal colour), no
    // creative look. Log: the file untouched, no profile and no look.
    object Original : LookItem("Rec.709", "")
    object Log : LookItem("Log", "")
    class Asset(name: String, val path: String, category: String) : LookItem(name, category)
    class External(name: String, val cube: FloatCube, val full: Boolean, val id: Int) : LookItem(name, LookCategories.MY_LUTS)
    // Diagnostic effects that bypass the input transform and every LUT file.
    class Diag(name: String, val kind: Int) : LookItem(name, LookCategories.DIAGNOSTICS)

    val favKey: String? get() = when (this) {
        is Asset -> path
        is External -> "external:$name"
        else -> null
    }
}

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var exportButton: MaterialButton
    private lateinit var profileButton: MaterialButton
    private lateinit var chips: ChipGroup
    private lateinit var looksList: RecyclerView
    private lateinit var looksAdapter: LooksAdapter
    private lateinit var layersAdapter: LayersAdapter
    private lateinit var layersPanel: View
    private lateinit var strengthSlider: Slider
    private lateinit var tips: Tips
    private lateinit var compareBadge: TextView
    private lateinit var backCallback: OnBackPressedCallback
    private var loadedInputCount = 0

    private val inputs = ArrayList<InputEntry>()
    private val looks = ArrayList<LookItem>()
    private val floatCache = HashMap<String, FloatCube>()
    private var selectedInput = 0

    // The layer stack, bottom first: layer 0 applies first and shows leftmost. The looks grid edits
    // the selected layer.
    private val layers = mutableListOf(Layer(0))
    private val selectedLayer get() = layersAdapter.selected
    private val selectedLook get() = layers[selectedLayer].look
    private var externalCount = 0
    private var requestId = 0
    private var videoUri: Uri? = null
    // The bundled S-Log3 sample shown before a video is opened. It loops, can't be paused or
    // exported, and forces the S-Log3 profile without saving that as the user's choice.
    private var referenceMode = false
    private lateinit var referenceBadge: View
    private var transformer: Transformer? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val thumbIo = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("lutapply", MODE_PRIVATE) }

    private var currentCube: Array<Array<IntArray>>? = null
    private var currentEffects: List<Effect> = emptyList()
    private var diagMode = 0
    private var effectsApplied = 0
    private var firstFrame = false
    private var errorCount = 0
    private var effectsDisabled = false
    private var effectsOffAt = 0L
    private var effectsError: String? = null
    private val identityCube by lazy { CubeParser.pack(LutMath.compose(null, null, 17)) }

    // The one LUT effect the preview uses. Changing looks updates its contents rather than the
    // player's effect list, so the GPU pipeline isn't rebuilt on every tap.
    private lateinit var liveLut: LiveColorLut
    private var lutEffects: List<Effect> = emptyList()
    private var lookCube: Array<Array<IntArray>>? = null

    // A new pipeline gets a new LiveColorLut, so the old pipeline's release can't delete its texture.
    private fun newLutEffects(): List<Effect> {
        liveLut = LiveColorLut(if (comparing) identityCube else lookCube ?: identityCube)
        lutEffects = listOf(liveLut)
        return lutEffects
    }

    // Recent baked stacks, so stepping back to a previous combination is instant. Bounded because a
    // strength drag produces a new combination at every step.
    private val bakedCache = object : LinkedHashMap<String, Pair<Array<Array<IntArray>>, FloatCube>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Array<Array<IntArray>>, FloatCube>>) = size > 16
    }

    private var advKeepColour = false
    private var advDiag = false
    private var fullLut = false
    private var comparing = false
    private var fullscreen = false
    private var profileUndetected = false
    private var profileDetected = false
    private var categoryFilter = FILTER_ALL
    // Settings (overflow menu): looks as one sideways row per category, and hiding the category
    // bar while the looks are scrolled.
    private var carouselLayout = false
    private var hideChipsOnScroll = true
    // Categories switched off in settings: left out of the category bar and the "All" list.
    private val hiddenCategories = HashSet<String>()
    private val favourites = HashSet<String>()

    private var thumbFrame: Bitmap? = null
    private var thumbFrameMs = -1L
    @Volatile private var thumbGeneration = 0
    private val captureThumb = Runnable {
        val ms = player.currentPosition
        if (abs(ms - thumbFrameMs) > 50) captureThumbnailFrame(ms)
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openVideo(uri)
    }

    private val pickLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadExternalLut(uri)
    }

    private val pickInputLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadInputLut(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hold the Cat Daddy splash long enough to read on a fresh start. Not on recreate(), which
        // the menu toggles use, or the screen would freeze for a moment each time.
        val splash = installSplashScreen()
        if (savedInstanceState == null) {
            val splashUntil = SystemClock.uptimeMillis() + SPLASH_MIN_MS
            splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() < splashUntil }
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        debug = findViewById(R.id.debug)
        progress = findViewById(R.id.progress)
        exportButton = findViewById(R.id.export)
        profileButton = findViewById(R.id.profileButton)
        chips = findViewById(R.id.categoryChips)
        looksList = findViewById(R.id.looksList)
        referenceBadge = findViewById(R.id.referenceBadge)
        compareBadge = findViewById(R.id.compareBadge)
        playerView = findViewById(R.id.player)
        tips = Tips(this, prefs)

        advKeepColour = prefs.getBoolean("adv_keepcolour", false)
        advDiag = prefs.getBoolean("adv_diag", false)
        favourites.addAll(prefs.getStringSet("favourites", emptySet()) ?: emptySet())
        categoryFilter = prefs.getString("filter", FILTER_ALL) ?: FILTER_ALL
        carouselLayout = prefs.getBoolean("looks_carousel", false)
        hideChipsOnScroll = prefs.getBoolean("hide_chips_on_scroll", true)
        hiddenCategories.addAll(prefs.getStringSet("hidden_categories", emptySet()) ?: emptySet())
        debug.visibility = if (advDiag) View.VISIBLE else View.GONE

        setupToolbar()

        val baseExtractors = DefaultExtractorsFactory()
        val extractors: ExtractorsFactory = if (advKeepColour) baseExtractors else StripColorInfoExtractorsFactory(baseExtractors)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .build()
        currentEffects = newLutEffects()
        player.setVideoEffects(currentEffects)
        effectsApplied++
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
                updateDebug()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) = updateDebug()

            override fun onTracksChanged(tracks: Tracks) = updateDebug()

            // Thumbnails follow the paused frame.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) scheduleThumbCapture()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) scheduleThumbCapture()
            }

            override fun onPlayerError(error: PlaybackException) {
                errorCount++
                val cause = error.cause?.let { " / ${it.javaClass.simpleName}: ${it.message}" } ?: ""
                val msg = "${error.errorCodeName}: ${error.message}$cause"
                Log.e(TAG, "Playback error (effects ${if (effectsDisabled) "off" else "on"}): $msg", error)
                if (!effectsDisabled) {
                    // Retry once without effects so the video still plays. The next look picked
                    // tries the effects again (see applyLut).
                    effectsDisabled = true
                    effectsOffAt = SystemClock.uptimeMillis()
                    effectsError = msg
                    val position = player.currentPosition
                    player.setVideoEffects(emptyList())
                    player.seekTo(position)
                    player.prepare()
                    player.playWhenReady = true
                    showEffectsOff()
                } else {
                    setStatus("Playback error: $msg")
                }
                updateDebug()
            }
        })
        playerView.player = player
        playerView.setFullscreenButtonClickListener { setFullscreen(it) }

        findViewById<HoldToCompareLayout>(R.id.playerHolder).apply {
            onHoldStart = {
                if (videoUri != null && !effectsDisabled) {
                    startCompare()
                    true
                } else {
                    false
                }
            }
            onHoldEnd = { endCompare() }
            exclude = playerView.findViewById(androidx.media3.ui.R.id.exo_progress)
            passThrough = playerView.findViewById(androidx.media3.ui.R.id.exo_bottom_bar)
            onTap = { togglePlayback() }
            setupQuickScrub(this)
        }
        setupScrubbing()
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = setFullscreen(false)
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupInputs()
        setupLayers()
        setupLooks()
        // No applySelection() here: opening the sample or a shared video applies it. Doing it now
        // too would queue a bake of the saved profile first (Samsung's 65-point LUT takes many
        // seconds to parse on a slow device) and hold up the one that's actually shown.
        updateDebug()

        findViewById<View>(R.id.openVideo).setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        exportButton.setOnClickListener { startExport() }
        if (inputs.size == 1 && looks.size == 1) setStatus("No bundled LUTs found. Use the menu to load one.")
        tips.show("looks", looksList, R.string.tip_looks, Tips.Place.INSIDE_TOP)
        // Not on recreate(): the intent is still the original share and would reopen that clip.
        if (savedInstanceState == null && openSharedVideo(intent)) return
        openReference()
    }

    // A video shared from the gallery (Share > LUT Master) or opened with "Open with".
    // singleTask means a share while the app is running lands here instead of a second copy.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSharedVideo(intent)
    }

    private fun openSharedVideo(intent: Intent?): Boolean {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return false
        openVideo(uri)
        return true
    }

    // Copied out of the APK once: MediaMetadataRetriever (thumbnails) can't read asset:// URIs.
    private fun openReference() {
        val file = File(filesDir, REFERENCE_ASSET.substringAfterLast('/'))
        try {
            // Recopied when an update ships a different clip (mp4 is stored uncompressed, so
            // openFd gives its real length).
            val length = try { assets.openFd(REFERENCE_ASSET).use { it.length } } catch (e: Exception) { -1L }
            if (!file.exists() || file.length() != length) {
                val tmp = File(filesDir, file.name + ".tmp")
                assets.open(REFERENCE_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reference clip unavailable", e)
            return
        }
        openVideo(Uri.fromFile(file), reference = true)
    }

    // There is no play/pause overlay: the centre button and the dark scrim are removed, and a tap
    // on the video plays or pauses it (see togglePlayback). The bottom bar with the seek bar and
    // fullscreen button shows while paused and hides when playback resumes.
    private fun setupScrubbing() {
        playerView.setTimeBarScrubbingEnabled(true)
        val bar = playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress) ?: return
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // Both: Media3 shows the centre group again when it leaves its compact layout.
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)?.visibility = View.GONE
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.visibility = View.GONE
        bar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                // A finished video keeps playWhenReady set and would play on from the new spot.
                // Scrubbing is for finding a frame, so if it wasn't playing it stays paused.
                if (!player.isPlaying) player.playWhenReady = false
                player.setScrubbingModeEnabled(true)
            }

            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) = Unit

            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                player.setScrubbingModeEnabled(false)
            }
        })
    }

    private fun togglePlayback(): Boolean {
        if (videoUri == null || player.mediaItemCount == 0) return false
        if (referenceMode) return true
        if (player.isPlaying) {
            player.playWhenReady = false
            playerView.showController()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
            playerView.hideController()
        }
        return true
    }

    // With the controls hidden, only a thin progress line is left and Media3 treats a swipe on it as
    // a tap that brings the controls up. Instead, a sideways drag along the bottom of the video
    // scrubs straight away and the controls stay hidden; a plain tap still shows them.
    private fun setupQuickScrub(holder: HoldToCompareLayout) {
        var wasPlaying = false
        holder.quickScrubEnabled = {
            videoUri != null && !referenceMode && !playerView.isControllerFullyVisible && player.duration > 0
        }
        holder.onQuickScrubStart = {
            // isPlaying, not playWhenReady: a finished video keeps playWhenReady set and would
            // start playing again from wherever the scrub stopped.
            wasPlaying = player.isPlaying
            player.playWhenReady = false
            player.setScrubbingModeEnabled(true)
        }
        holder.onQuickScrubTo = { fraction -> player.seekTo((fraction * player.duration).toLong()) }
        holder.onQuickScrubEnd = {
            player.setScrubbingModeEnabled(false)
            player.playWhenReady = wasPlaying
            scheduleThumbCapture()
        }
    }

    override fun onDestroy() {
        tips.dismiss()
        transformer?.cancel()
        player.release()
        io.shutdown()
        thumbIo.shutdownNow()
        super.onDestroy()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_profile -> { showProfilePicker(); true }
                R.id.menu_help -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.how_it_works)
                        .setMessage(R.string.help_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    true
                }
                R.id.menu_load_look -> { pickLut.launch(arrayOf("*/*")); true }
                R.id.menu_categories -> { showCategoryPicker(); true }
                R.id.menu_carousel -> {
                    carouselLayout = !carouselLayout
                    item.isChecked = carouselLayout
                    prefs.edit().putBoolean("looks_carousel", carouselLayout).apply()
                    looksAdapter.resetStripScroll()
                    applyLooksLayout()
                    rebuildLooksRows()
                    looksList.scrollToPosition(0)
                    true
                }
                R.id.menu_hide_chips -> {
                    hideChipsOnScroll = !hideChipsOnScroll
                    item.isChecked = hideChipsOnScroll
                    prefs.edit().putBoolean("hide_chips_on_scroll", hideChipsOnScroll).apply()
                    if (!hideChipsOnScroll) setChipsShown(findViewById(R.id.chipScroll), true)
                    true
                }
                R.id.menu_full_lut -> {
                    fullLut = !fullLut
                    item.isChecked = fullLut
                    true
                }
                // The player is built once per screen, so these take effect by recreating the activity.
                R.id.menu_keep_colour -> {
                    prefs.edit().putBoolean("adv_keepcolour", !advKeepColour).apply()
                    recreate()
                    true
                }
                R.id.menu_show_diag -> {
                    prefs.edit().putBoolean("adv_diag", !advDiag).apply()
                    recreate()
                    true
                }
                else -> false
            }
        }
        toolbar.menu.findItem(R.id.menu_carousel)?.isChecked = carouselLayout
        toolbar.menu.findItem(R.id.menu_hide_chips)?.isChecked = hideChipsOnScroll
        toolbar.menu.findItem(R.id.menu_keep_colour)?.isChecked = advKeepColour
        toolbar.menu.findItem(R.id.menu_show_diag)?.isChecked = advDiag
    }

    private fun openVideo(uri: Uri, reference: Boolean = false) {
        val wasReference = referenceMode
        referenceMode = reference
        videoUri = uri
        firstFrame = false
        effectsDisabled = false
        referenceBadge.visibility = if (reference) View.VISIBLE else View.GONE
        player.repeatMode = if (reference) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        playerView.useController = !reference
        // The sample loops, and each loop would flash the buffering spinner.
        playerView.setShowBuffering(if (reference) PlayerView.SHOW_BUFFERING_NEVER else PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        if (reference) {
            inputs.indexOfFirst { it.path?.contains(REFERENCE_PROFILE) == true }.takeIf { it >= 0 }?.let { selectedInput = it }
            profileDetected = false
            updateProfileButton()
        } else if (wasReference) {
            selectedInput = savedInputIndex()
            updateProfileButton()
        }
        // Swapping the media under a running effects pipeline can fail with "Textures are all in
        // use" (frames of the old clip still in flight). Stop first so the new clip starts on an
        // idle pipeline.
        if (player.mediaItemCount > 0) {
            player.stop()
            player.clearMediaItems()
        }
        // Media3 requires setVideoEffects at least once before prepare() to set up the effects pipeline.
        if (currentEffects === lutEffects) currentEffects = newLutEffects()
        player.setVideoEffects(currentEffects)
        effectsApplied++
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        thumbFrame = null
        thumbFrameMs = -1L
        thumbGeneration++
        looksAdapter.clearThumbnails()
        captureThumbnailFrame(500L)
        profileUndetected = false
        if (!reference) detectProfile(uri)
        updateExportEnabled()
        applySelection()
        updateDebug()
        tips.show("compare", findViewById(R.id.playerHolder), R.string.tip_compare, Tips.Place.INSIDE_TOP)
    }

    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        if (on) tips.dismiss()
        for (id in intArrayOf(R.id.toolbar, R.id.controlsArea)) {
            findViewById<View>(id).visibility = vis
        }
        updateLayersPanel()
        updateProfileRow()
        if (advDiag) debug.visibility = vis
        playerView.setFullscreenButtonState(on)
        backCallback.isEnabled = on
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun startCompare() {
        comparing = true
        compareBadge.visibility = View.VISIBLE
        if (currentEffects !== lutEffects) swapEffects(newLutEffects()) else { liveLut.set(identityCube); redrawIfPaused() }
    }

    private fun endCompare() {
        comparing = false
        compareBadge.visibility = View.GONE
        liveLut.set(lookCube ?: identityCube)
        if (currentEffects !== lutEffects) swapEffects(currentEffects) else redrawIfPaused()
    }

    // A paused player only draws when a new frame arrives, so seek to push the same frame through the
    // new LUT. ExoPlayer ignores a seek to the current millisecond while paused, so nudge by 1 ms:
    // the same frame is shown, but it is decoded and drawn again. (VideoFrameProcessor.REDRAW was
    // tried and showed the frame without any effects on the emulator.)
    private fun redrawIfPaused() {
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE || player.isPlaying) return
        val controlsShown = playerView.isControllerFullyVisible
        seekToRedraw(player.currentPosition)
        if (!controlsShown) playerView.hideController()
    }

    // Seeks to (almost exactly) a position in a way ExoPlayer won't skip, so the frame is redrawn.
    private fun seekToRedraw(position: Long) {
        val duration = player.duration
        val target = if (duration != C.TIME_UNSET && position + 1 >= duration) maxOf(0L, position - 1) else position + 1
        player.seekTo(target)
    }

    private fun swapEffects(effects: List<Effect>) {
        val position = player.currentPosition
        val playing = player.playWhenReady
        // Changing the grade shouldn't bring up the controls. They only appear when the video is tapped.
        val controlsShown = playerView.isControllerFullyVisible
        player.setVideoEffects(effects)
        effectsApplied++
        if (player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE) {
            // A paused player only redraws on a new frame, so seek to push one through.
            if (!playing) seekToRedraw(position)
            else if (abs(player.currentPosition - position) > 300) player.seekTo(position)
        }
        player.playWhenReady = playing
        if (!controlsShown) playerView.hideController()
        updateDebug()
    }

    private fun assetCubes(dir: String): List<String> =
        (assets.list(dir) ?: emptyArray()).filter { it.endsWith(".cube", ignoreCase = true) }.sorted()

    // ---- Camera profile ----------------------------------------------------------------------

    private fun setupInputs() {
        assetCubes("luts/input").forEach { inputs.add(InputEntry(LutNames.pretty(it), "luts/input/$it")) }
        // Input LUTs the user loaded earlier (kept in app-private storage, never bundled).
        storedInputFiles().forEach { f ->
            try {
                val cube = CubeParser.parseFloat(f.readText())
                inputs.add(InputEntry(LutNames.pretty(f.name) + " (loaded)", null, cube, ++loadedInputCount, f))
            } catch (e: Exception) {
                // A stored file that no longer parses can't be removed from the UI, so delete it.
                f.delete()
            }
        }
        inputs.add(InputEntry("Raw (no transform)", null))
        selectedInput = savedInputIndex()
        profileButton.setOnClickListener { showProfilePicker() }
        updateProfileButton()
    }

    // A saved choice wins, then the bundled Samsung Log LUT, then the Rec.709 passthrough.
    private fun savedInputIndex(): Int {
        val saved = prefs.getString("input", null)
        return inputs.indexOfFirst { it.key == saved }.takeIf { it >= 0 }
            ?: inputs.indexOfFirst { it.isSamsung }.takeIf { it >= 0 }
            ?: inputs.indexOfFirst { it.isPassthrough }.takeIf { it >= 0 }
            ?: 0
    }

    private fun updateProfileButton() {
        val name = inputs[selectedInput].name
        profileButton.text = if (profileDetected) "$name ${getString(R.string.detected_suffix)}" else name
        updateProfileRow()
    }

    // The camera profile rarely changes, so its row only shows when detection failed for the current
    // video. Otherwise it lives in the overflow menu.
    private fun updateProfileRow() {
        val show = !fullscreen && videoUri != null && profileUndetected
        findViewById<View>(R.id.profileRow).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun selectInput(index: Int, detected: Boolean = false) {
        val changed = index != selectedInput
        selectedInput = index
        profileDetected = detected
        prefs.edit().putString("input", inputs[index].key).apply()
        updateProfileButton()
        if (changed) onInputChanged()
    }

    private fun onInputChanged() {
        updateExportEnabled()
        applySelection()
        regenerateThumbnails()
    }

    private fun showProfilePicker() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_profile)
            .setSingleChoiceItems(inputs.map { it.name }.toTypedArray(), selectedInput) { d, which ->
                d.dismiss()
                selectInput(which)
            }
            .setNeutralButton(R.string.load_input_cube) { _, _ -> pickInputLut.launch(arrayOf("*/*")) }
        if (inputs[selectedInput].isLoaded) dialog.setNegativeButton(R.string.remove) { _, _ -> confirmRemoveInput() }
        dialog.show()
    }

    private fun detectProfile(uri: Uri) {
        io.execute {
            val fragment = try {
                ProfileDetector.detect(readEnds(uri))
            } catch (e: Exception) {
                null
            }
            main.post {
                if (uri != videoUri) return@post
                val index = fragment?.let { f -> inputs.indexOfFirst { it.path?.contains(f, ignoreCase = true) == true } } ?: -1
                if (index >= 0) {
                    profileUndetected = false
                    selectInput(index, detected = true)
                } else {
                    profileDetected = false
                    profileUndetected = true
                    updateProfileButton()
                    Snackbar.make(snackbarAnchor(), getString(R.string.not_detected, inputs[selectedInput].name), Snackbar.LENGTH_LONG)
                        .setAction(R.string.change) { showProfilePicker() }
                        .show()
                }
            }
        }
    }

    // Camera metadata can sit at either end of an MP4 (moov first or last), so read both ends.
    private fun readEnds(uri: Uri): ByteArray {
        val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        contentResolver.openInputStream(uri)!!.use { s ->
            val head = readUpTo(s, SCAN_BYTES)
            if (size in 0..(2L * SCAN_BYTES)) return head + readUpTo(s, SCAN_BYTES)
            var toSkip = if (size > 0) size - SCAN_BYTES - head.size else 0L
            while (toSkip > 0) {
                val n = s.skip(toSkip)
                if (n <= 0) break
                toSkip -= n
            }
            return head + readUpTo(s, SCAN_BYTES)
        }
    }

    private fun readUpTo(s: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    private fun storedInputFiles(): List<File> =
        File(filesDir, "inputs").listFiles { f -> f.isFile && f.name.endsWith(".cube") }?.sortedBy { it.name } ?: emptyList()

    private fun confirmRemoveInput() {
        val entry = inputs[selectedInput]
        if (!entry.isLoaded) return
        MaterialAlertDialogBuilder(this)
            .setMessage("Remove ${entry.name} from this app? You can load it again from the file.")
            .setPositiveButton(R.string.remove) { _, _ -> removeInput(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeInput(entry: InputEntry) {
        entry.file?.delete()
        inputs.remove(entry)
        selectedInput = inputs.indexOfFirst { it.isPassthrough }.takeIf { it >= 0 } ?: 0
        profileDetected = false
        prefs.edit().putString("input", inputs[selectedInput].key).apply()
        updateProfileButton()
        onInputChanged()
    }

    // ---- Looks -------------------------------------------------------------------------------

    // Looks live in luts/looks/<category>/ folders. Files directly in luts/looks are placed by name.
    private fun discoverLooks(): List<LookItem.Asset> {
        val out = ArrayList<LookItem.Asset>()
        for (entry in (assets.list("luts/looks") ?: emptyArray()).sorted()) {
            if (entry.endsWith(".cube", ignoreCase = true)) {
                out.add(LookItem.Asset(LutNames.pretty(entry), "luts/looks/$entry", LookCategories.categorise(entry)))
            } else {
                val category = LookCategories.folderTitle(entry)
                assetCubes("luts/looks/$entry").forEach {
                    out.add(LookItem.Asset(LutNames.pretty(it), "luts/looks/$entry/$it", category))
                }
            }
        }
        return out
    }

    private fun setupLooks() {
        looks.add(LookItem.Original)
        looks.add(LookItem.Log)
        looks.addAll(discoverLooks())
        if (advDiag) {
            looks.add(LookItem.Diag("DIAG 1: swap colour channels (RgbMatrix)", 1))
            looks.add(LookItem.Diag("DIAG 2: invert colours (SingleColorLut)", 2))
        }
        looksAdapter = LooksAdapter(
            onSelected = { selectLook(it) },
            onToggleFavourite = { toggleFavourite(it) },
            isFavourite = { it in favourites },
        )
        // Strip tiles are as wide as grid tiles: the list's width over the column count, less the
        // tile's 4dp margin on each side.
        val density = resources.displayMetrics.density
        looksAdapter.stripTileWidth =
            (((resources.configuration.screenWidthDp - 16) / lookColumns().toFloat() - 8) * density).roundToInt()
        looksList.adapter = looksAdapter
        applyLooksLayout()
        looksAdapter.selectedIndex = selectedLook
        rebuildChips()
        rebuildLooksRows()
        setupChipAutoHide()
    }

    private fun lookColumns() = maxOf(3, (resources.configuration.screenWidthDp - 16) / 116)

    // Grid: headers across the full width, tiles in columns. Carousel: a vertical list of
    // headers and sideways strips.
    private fun applyLooksLayout() {
        looksList.layoutManager = if (carouselLayout) {
            LinearLayoutManager(this)
        } else {
            val span = lookColumns()
            GridLayoutManager(this, span).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (looksAdapter.isHeader(position)) span else 1
                }
            }
        }
    }

    // The category bar slides away while the looks are scrolled down and comes back on the way
    // up or at the top, giving the looks more room. Off in settings keeps it always shown.
    private fun setupChipAutoHide() {
        val bar = findViewById<View>(R.id.chipScroll)
        // The list starts below the floating bar: pad it by the bar's height.
        bar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (looksList.paddingTop != h) {
                looksList.setPadding(looksList.paddingLeft, h, looksList.paddingRight, looksList.paddingBottom)
            }
        }
        val slop = 12 * resources.displayMetrics.density
        var travel = 0f
        looksList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!hideChipsOnScroll || dy == 0) return
                travel = if ((travel > 0) == (dy > 0)) travel + dy else dy.toFloat()
                when {
                    !rv.canScrollVertically(-1) || travel < -slop -> setChipsShown(bar, true)
                    travel > slop -> setChipsShown(bar, false)
                }
            }
        })
    }

    private var chipsShown = true

    // Slides the bar up out of view (clipped by its frame) or back down. No layout change.
    private fun setChipsShown(bar: View, shown: Boolean) {
        if (chipsShown == shown) return
        chipsShown = shown
        bar.animate().cancel()
        if (shown) {
            bar.visibility = View.VISIBLE
            bar.animate().translationY(0f).alpha(1f).setDuration(160).start()
        } else {
            bar.animate().translationY(-bar.height.toFloat()).alpha(0f).setDuration(160)
                .withEndAction { if (!chipsShown) bar.visibility = View.INVISIBLE }.start()
        }
    }

    private fun selectLook(index: Int) {
        if (comparing) endCompare()
        val layer = layers[selectedLayer]
        if (layer.look == index) return
        layer.look = index
        looksAdapter.selectedIndex = index
        layersAdapter.reset(selectedLayer)
        updateLayersPanel()
        updateExportEnabled()
        applySelection()
    }

    // ---- Layers ------------------------------------------------------------------------------

    private fun setupLayers() {
        layersPanel = findViewById(R.id.layersPanel)
        strengthSlider = findViewById(R.id.strength)
        // No percentage text: the value shows in a bubble over the thumb only while dragging.
        strengthSlider.setLabelFormatter { "${it.roundToInt()}%" }
        layersAdapter = LayersAdapter(
            layers,
            nameOf = { l -> if (l.look == 0) null else looks.getOrNull(l.look)?.name },
            onSelect = { selectLayer(it) },
            onRemove = { removeLayer(it) },
            onMoved = {
                layersAdapter.reset(selectedLayer)
                applySelection()
                regenerateThumbnails()
            },
        )
        val list = findViewById<RecyclerView>(R.id.layersList)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.itemAnimator = null
        layersAdapter.attach(list)
        findViewById<MaterialButton>(R.id.addLayer).setOnClickListener { addLayer() }
        strengthSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            layers[selectedLayer].strength = value / 100f
            applySelection()
        }
        // The other layers' previews depend on this one's strength, so refresh them on release.
        strengthSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                if (layers.size > 1) regenerateThumbnails()
            }
        })
        updateStrength()
    }

    // The panel only appears once there's a look to stack on.
    private fun updateLayersPanel() {
        val show = !fullscreen && (layers.size > 1 || layers[0].look != 0)
        val was = layersPanel.visibility == View.VISIBLE
        layersPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !was) tips.show("layers", findViewById(R.id.addLayer), R.string.tip_layers, Tips.Place.BELOW)
    }

    private fun updateStrength() {
        val pct = (layers[selectedLayer].strength * 100).roundToInt()
        strengthSlider.value = (pct / 5 * 5).toFloat()
    }

    private fun selectLayer(pos: Int) {
        if (pos == selectedLayer) return
        var target = pos
        // Moving off a layer that never got a look drops it.
        val old = selectedLayer
        if (layers[old].look == 0 && layers.size > 1) {
            layers.removeAt(old)
            if (old < target) target--
            layersAdapter.reset(target)
        } else {
            layersAdapter.select(target)
        }
        looksAdapter.selectedIndex = selectedLook
        updateStrength()
        regenerateThumbnails()
    }

    // A new layer goes on top of the stack (rightmost) and is selected, so the next look tapped fills it.
    private fun addLayer() {
        val empty = layers.indexOfFirst { it.look == 0 }
        if (empty >= 0) {
            selectLayer(empty)
            return
        }
        if (layers.size >= MAX_LAYERS) {
            Snackbar.make(snackbarAnchor(), getString(R.string.max_layers, MAX_LAYERS), Snackbar.LENGTH_SHORT).show()
            return
        }
        layers.add(Layer(0))
        layersAdapter.reset(layers.size - 1)
        findViewById<RecyclerView>(R.id.layersList).scrollToPosition(layers.size - 1)
        looksAdapter.selectedIndex = 0
        updateStrength()
        regenerateThumbnails()
    }

    // Removing the last layer just empties it, which hides the panel again.
    private fun removeLayer(pos: Int) {
        if (comparing) endCompare()
        var sel = selectedLayer
        if (layers.size == 1) {
            layers[0].look = 0
            layers[0].strength = 1f
        } else {
            layers.removeAt(pos)
            if (pos < sel || sel >= layers.size) sel--
        }
        layersAdapter.reset(sel.coerceIn(0, layers.size - 1))
        looksAdapter.selectedIndex = selectedLook
        updateStrength()
        updateLayersPanel()
        updateExportEnabled()
        applySelection()
        regenerateThumbnails()
    }

    private fun diagLook(): LookItem.Diag? = layers.firstNotNullOfOrNull { looks[it.look] as? LookItem.Diag }

    // Layers that change the picture, bottom first.
    private fun activeStack(): List<Pair<LookItem, Float>> =
        layers.map { looks[it.look] to it.strength }
            .filter { (look, _) -> look !is LookItem.Original && look !is LookItem.Log && look !is LookItem.Diag }

    // A layer set to Log turns the camera profile off.
    private fun logSelected(): Boolean = layers.any { looks[it.look] is LookItem.Log }

    private fun layerCube(look: LookItem): FloatCube = when (look) {
        is LookItem.Asset -> loadAsset(look.path)
        is LookItem.External -> look.cube
        else -> throw IllegalStateException("${look.name} is not a LUT")
    }

    // Favourites show at the top of the list as well as in their own category, so the rows are
    // rebuilt; every copy of the look gets the new star.
    private fun toggleFavourite(key: String) {
        if (!favourites.remove(key)) favourites.add(key)
        prefs.edit().putStringSet("favourites", HashSet(favourites)).apply()
        rebuildLooksRows()
    }

    private fun sortedCategories(): List<String> =
        looks.map { it.category }.filter { it.isNotEmpty() }.distinct()
            .sortedWith(compareBy({ LookCategories.sortKey(it) }, { it }))

    private fun shownCategories() = sortedCategories().filter { it !in hiddenCategories }

    // Tick boxes for every category, all on by default. Off ones leave the category bar and the
    // "All" list; looks already starred still show under Favourites.
    private fun showCategoryPicker() {
        val all = sortedCategories().filter { it != LookCategories.DIAGNOSTICS }
        val checked = BooleanArray(all.size) { all[it] !in hiddenCategories }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.categories_title)
            .setMultiChoiceItems(all.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setNeutralButton(R.string.categories_all_on) { _, _ ->
                hiddenCategories.clear()
                saveHiddenCategories()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                hiddenCategories.removeAll(all.toSet())
                all.forEachIndexed { i, c -> if (!checked[i]) hiddenCategories.add(c) }
                saveHiddenCategories()
            }
            .show()
    }

    private fun saveHiddenCategories() {
        prefs.edit().putStringSet("hidden_categories", HashSet(hiddenCategories)).apply()
        looksAdapter.resetStripScroll()
        rebuildChips()
        rebuildLooksRows()
        regenerateThumbnails()
    }

    private fun rebuildChips() {
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        val filters = listOf(FILTER_ALL, FILTER_FAVS) + shownCategories()
        if (categoryFilter !in filters) categoryFilter = FILTER_ALL
        for (f in filters) {
            chips.addView(Chip(this).apply {
                id = View.generateViewId()
                tag = f
                text = when (f) {
                    FILTER_ALL -> getString(R.string.chip_all)
                    FILTER_FAVS -> getString(R.string.chip_favourites)
                    else -> f
                }
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = f == categoryFilter
            })
        }
        chips.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            categoryFilter = chip.tag as String
            prefs.edit().putString("filter", categoryFilter).apply()
            looksAdapter.resetStripScroll()
            rebuildLooksRows()
            looksList.scrollToPosition(0)
            regenerateThumbnails()
        }
    }

    // Rec.709 (profile only) and Log (untouched) always come first, so both are one tap away in
    // every view. In "All", favourites follow them in their own section, and also stay in their category.
    private fun rebuildLooksRows() {
        fun row(i: Int) = LooksRow.Look(i, looks[i].name, looks[i].favKey)
        fun isFav(i: Int) = looks[i].favKey?.let { it in favourites } == true
        val shown = (FIRST_LOOK until looks.size).filter { i ->
            when (categoryFilter) {
                FILTER_ALL -> looks[i].category !in hiddenCategories
                FILTER_FAVS -> isFav(i)
                else -> looks[i].category == categoryFilter
            }
        }
        // Sections in order: (header or null, looks).
        val sections = ArrayList<Pair<String?, List<Int>>>()
        when (categoryFilter) {
            FILTER_ALL, FILTER_FAVS -> {
                // "All": Rec.709 and Log lead the Favourites section (no header if there are none yet).
                // Favourites from switched-off categories still show here.
                val favs = if (categoryFilter == FILTER_ALL) (FIRST_LOOK until looks.size).filter(::isFav) else emptyList()
                sections.add((if (favs.isNotEmpty()) getString(R.string.chip_favourites) else null) to listOf(0, 1) + favs)
                if (categoryFilter == FILTER_FAVS && shown.isEmpty()) {
                    sections.add(getString(R.string.no_favourites) to emptyList())
                }
                shown.groupBy { looks[it].category }
                    .toSortedMap(compareBy<String>({ LookCategories.sortKey(it) }, { it }))
                    .forEach { (category, indices) -> sections.add(category to indices) }
            }
            // One category: a single run of looks, Rec.709 and Log first.
            else -> sections.add(null to listOf(0, 1) + shown)
        }

        val rows = ArrayList<LooksRow>()
        sections.forEachIndexed { n, (title, indices) ->
            if (title != null) rows.add(LooksRow.Header(title))
            if (indices.isEmpty()) return@forEachIndexed
            if (carouselLayout) rows.add(LooksRow.Strip("$n:$title", indices.map(::row)))
            else indices.forEach { rows.add(row(it)) }
        }
        looksAdapter.rows = rows
    }

    // ---- Thumbnails --------------------------------------------------------------------------

    private fun scheduleThumbCapture() {
        if (videoUri == null || comparing) return
        main.removeCallbacks(captureThumb)
        main.postDelayed(captureThumb, 500)
    }

    private fun captureThumbnailFrame(positionMs: Long) {
        val uri = videoUri ?: return
        thumbIo.execute {
            val frame = try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this, uri)
                    r.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                } finally {
                    r.release()
                }
            } catch (e: Exception) {
                null
            } ?: return@execute
            val thumb = shrink(frame, THUMB_LONG_SIDE)
            main.post {
                if (uri != videoUri) return@post
                thumbFrame = thumb
                thumbFrameMs = positionMs
                regenerateThumbnails()
            }
        }
    }

    // Grades the thumbnail frame with every look as it would look in the selected layer, with the
    // layers below and above it in place. Visible tiles first. A newer run cancels an older one.
    private fun regenerateThumbnails() {
        val base = thumbFrame ?: return
        val gen = ++thumbGeneration
        val input = inputs[selectedInput]
        val visible = looksAdapter.visibleLookIndices()
        val visibleSet = visible.toHashSet()
        val order = (visible + looks.indices.filter { it !in visibleSet }).map { it to looks[it] }
        fun usable(l: Layer) = looks[l.look].let { it !is LookItem.Original && it !is LookItem.Log && it !is LookItem.Diag }
        val otherLog = layers.filterIndexed { i, _ -> i != selectedLayer }.any { looks[it.look] is LookItem.Log }
        val sel = selectedLayer
        val below = layers.subList(0, sel).filter(::usable).map { looks[it.look] to it.strength }
        val above = layers.subList(sel + 1, layers.size).filter(::usable).map { looks[it.look] to it.strength }
        val w = base.width
        val h = base.height
        thumbIo.execute {
            val src = IntArray(w * h)
            base.getPixels(src, 0, w, 0, 0, w, h)
            val first = try {
                inputCube(input)
            } catch (e: Exception) {
                null
            }
            val others = try {
                below.map { (l, st) -> l to (layerCube(l) to st) } to above.map { (l, st) -> l to (layerCube(l) to st) }
            } catch (e: Exception) {
                return@execute
            }
            for ((index, look) in order) {
                if (gen != thumbGeneration) return@execute
                if (look is LookItem.Diag) continue
                try {
                    val candidate = if (look is LookItem.Original || look is LookItem.Log) emptyList() else listOf(look to (layerCube(look) to 1f))
                    val stack = others.first + candidate + others.second
                    val skipInput = otherLog || look is LookItem.Log || stack.any { (l, _) -> l is LookItem.External && l.full }
                    val px = src.copyOf()
                    val stageOne = if (skipInput) null else first
                    if (stageOne != null || stack.isNotEmpty()) LutMath.applyToPixels(LutMath.bake(stageOne, stack.map { it.second }, 17), px)
                    val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                    main.post { if (gen == thumbGeneration) looksAdapter.setThumbnail(index, bmp) }
                } catch (e: Exception) {
                    // A look that fails to load just keeps a blank tile.
                }
            }
        }
    }

    private fun inputCube(input: InputEntry): FloatCube? = when {
        input.isRaw || input.isPassthrough -> null
        input.isLoaded -> input.cube
        else -> loadAsset(input.path!!)
    }

    // ---- LUT resolution and preview -----------------------------------------------------------

    private fun loadAsset(path: String): FloatCube =
        synchronized(floatCache) { floatCache[path] }
            ?: CubeParser.parseFloat(assets.open(path).bufferedReader().use { it.readText() }).also {
                synchronized(floatCache) { floatCache[path] = it }
            }

    // Bakes the input transform and the layer stack into one 33-point LUT. Null means no effect.
    private fun resolve(onReady: (cube: Array<Array<IntArray>>?, floatCube: FloatCube?, label: String, fileLabel: String) -> Unit) {
        val input = inputs[selectedInput]
        val stack = activeStack()
        val skipInput = logSelected() || stack.any { (l, _) -> l is LookItem.External && l.full }
        val inputKey = when {
            skipInput || input.isRaw || input.isPassthrough -> null
            input.isLoaded -> "loaded-${input.id}"
            else -> input.path
        }
        val parts = ArrayList<String>()
        if (!skipInput) parts.add(input.name)
        for ((look, strength) in stack) {
            parts.add(if (strength < 0.999f) "${look.name} ${(strength * 100).roundToInt()}%" else look.name)
        }
        if (parts.isEmpty()) parts.add("Raw")
        val label = parts.joinToString(" + ")
        val fileLabel = parts.joinToString("_") { LutNames.slug(it) }
        val request = ++requestId

        if (inputKey == null && stack.isEmpty()) {
            onReady(null, null, label, fileLabel)
            return
        }
        val key = (listOf(inputKey ?: "raw") + stack.map { (look, st) ->
            val id = when (look) {
                is LookItem.Asset -> look.path
                is LookItem.External -> "external-${look.id}"
                else -> look.name
            }
            "$id@${(st * 100).roundToInt()}"
        }).joinToString("|")
        bakedCache[key]?.let { (packed, baked) ->
            onReady(packed, baked, label, fileLabel)
            return
        }
        io.execute {
            // Dragging a strength slider queues many requests. Only the newest is worth baking.
            if (request != requestId) return@execute
            try {
                val first = when {
                    inputKey == null -> null
                    input.isLoaded -> input.cube
                    else -> loadAsset(input.path!!)
                }
                val baked = LutMath.bake(first, stack.map { (look, st) -> layerCube(look) to st }, 33)
                val packed = CubeParser.pack(baked)
                main.post {
                    bakedCache[key] = packed to baked
                    if (request == requestId) onReady(packed, baked, label, fileLabel)
                }
            } catch (e: Exception) {
                main.post { if (request == requestId) setStatus("LUT error ($label): ${e.message}") }
            }
        }
    }

    // Choosing an input or a look applies it to the playing or paused video straight away.
    private fun applySelection() {
        val look = diagLook()
        if (look != null) {
            requestId++
            currentCube = null
            diagMode = look.kind
            applyEffects(diagEffects(look.kind))
            setStatus("${look.name}: bypasses the input and every LUT. The picture should change drastically.")
            return
        }
        diagMode = 0
        resolve { cube, _, _, _ ->
            currentCube = cube
            applyLut(cube)
            // A preview error stays on screen for as long as the preview is plain.
            if (effectsDisabled) showEffectsOff() else clearStatus()
        }
    }

    // DIAG 1 rotates the colour channels with an RgbMatrix (red<-green, green<-blue, blue<-red), no LUT involved.
    private fun diagEffects(kind: Int): List<Effect> = when (kind) {
        1 -> listOf<Effect>(object : RgbMatrix {
            override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = floatArrayOf(
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                1f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f,
            )
        })
        else -> listOf<Effect>(SingleColorLut.createFromCube(invertCube))
    }

    private val invertCube by lazy {
        Array(17) { r ->
            Array(17) { g ->
                IntArray(17) { b ->
                    0xFF000000.toInt() or ((255 - r * 255 / 16) shl 16) or ((255 - g * 255 / 16) shl 8) or (255 - b * 255 / 16)
                }
            }
        }
    }

    private fun noEffect(): Boolean {
        val input = inputs[selectedInput]
        return activeStack().isEmpty() && diagLook() == null && (input.isRaw || input.isPassthrough || logSelected())
    }

    // An identity LUT stands in for "no effect" so the effects pipeline stays set up on the player.
    private fun applyLut(cube: Array<Array<IntArray>>?) {
        lookCube = cube
        if (!comparing) liveLut.set(cube ?: identityCube)
        if (effectsDisabled && videoUri != null && !comparing &&
            SystemClock.uptimeMillis() - effectsOffAt > EFFECTS_RETRY_MS
        ) {
            // The preview fell back to plain video after an error. A new look is a fresh chance:
            // rebuild the effects pipeline. If it fails again, onPlayerError turns it back off.
            effectsDisabled = false
            applyEffects(newLutEffects())
        } else if (currentEffects !== lutEffects) {
            // Coming back from a diagnostic effect: put the live LUT back in the pipeline.
            applyEffects(newLutEffects())
        } else {
            updateDebug()
            if (!effectsDisabled && !comparing) redrawIfPaused()
        }
    }

    private fun applyEffects(effects: List<Effect>) {
        currentEffects = effects
        if (effectsDisabled || comparing) {
            updateDebug()
            return
        }
        swapEffects(effects)
    }

    private fun updateDebug() {
        val f = player.videoFormat
        val src = if (f != null) "${f.width}x${f.height} | ${f.sampleMimeType}" else "no video yet"
        val mode = when {
            effectsDisabled -> "OFF after error"
            diagMode != 0 -> "DIAG $diagMode"
            currentCube == null -> "identity"
            else -> "LUT"
        }
        debug.text = "effects: $effectsApplied sets ($mode) | $src | firstFrame: ${if (firstFrame) "yes" else "no"} | errors: $errorCount"
    }

    // ---- Loading LUT files -------------------------------------------------------------------

    private fun loadInputLut(uri: Uri) {
        setStatus("Reading input LUT...")
        io.execute {
            try {
                val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val cube = CubeParser.parseFloat(text) // rejects 1D-only and unsupported files before anything is stored
                val dir = File(filesDir, "inputs").apply { mkdirs() }
                val file = File(dir, LutNames.slug(displayName(uri).removeSuffix(".cube").removeSuffix(".CUBE")) + ".cube")
                file.writeText(text)
                val name = LutNames.pretty(file.name) + " (loaded)"
                main.post {
                    val entry = InputEntry(name, null, cube, ++loadedInputCount, file)
                    val existing = inputs.indexOfFirst { it.file?.name == file.name }
                    if (existing >= 0) {
                        inputs[existing] = entry
                        selectedInput = existing
                    } else {
                        val at = inputs.indexOfFirst { it.isRaw }.let { if (it < 0) inputs.size else it }
                        inputs.add(at, entry)
                        selectedInput = at
                    }
                    profileDetected = false
                    prefs.edit().putString("input", entry.key).apply()
                    updateProfileButton()
                    onInputChanged()
                }
            } catch (e: Exception) {
                main.post { setStatus("Input LUT error: ${e.message}") }
            }
        }
    }

    private fun loadExternalLut(uri: Uri) {
        val full = fullLut
        setStatus("Reading LUT...")
        io.execute {
            try {
                val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val cube = CubeParser.parseFloat(text)
                val name = LutNames.pretty(displayName(uri)) + if (full) " (full)" else ""
                main.post {
                    // Loaded looks go before the DIAG items, which stay at the end of the list.
                    val at = looks.indexOfFirst { it is LookItem.Diag }.let { if (it < 0) looks.size else it }
                    looks.add(at, LookItem.External(name, cube, full, ++externalCount))
                    layers.forEach { if (it.look >= at) it.look++ }
                    categoryFilter = LookCategories.MY_LUTS
                    rebuildChips()
                    rebuildLooksRows()
                    selectLook(at)
                    regenerateThumbnails()
                }
            } catch (e: Exception) {
                main.post { setStatus("LUT error: ${e.message}") }
            }
        }
    }

    // ---- Export ------------------------------------------------------------------------------

    private fun startExport() {
        val uri = videoUri ?: return
        if (diagLook() != null) return
        resolve { cube, floatCube, label, fileLabel ->
            if (cube != null && floatCube != null) runExport(uri, label, fileLabel, cube, floatCube)
        }
    }

    private fun runExport(uri: Uri, label: String, fileLabel: String, cube: Array<Array<IntArray>>, lut: FloatCube) {
        exportButton.isEnabled = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        setStatus(getString(R.string.exporting))

        val out = File(cacheDir, "graded_${System.currentTimeMillis()}.mp4")
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), listOf<Effect>(SingleColorLut.createFromCube(cube))))
            .build()
        // Ignore HDR colour flags so the LUT acts on the raw code values, matching the preview.
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
            .build()

        val t = Transformer.Builder(this)
            .setAssetLoaderFactory(
                DefaultAssetLoaderFactory(
                    this,
                    DefaultDecoderFactory.Builder(this).build(),
                    Clock.DEFAULT,
                    DefaultMediaSourceFactory(this, StripLogTagExtractorsFactory(DefaultExtractorsFactory())),
                    DataSourceBitmapLoader.Builder(this).build(),
                ),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finishExport(out, fileLabel, uri, lut)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    out.delete()
                    transformer = null
                    progress.visibility = View.GONE
                    updateExportEnabled()
                    Log.w(TAG, "Export failed (${exportException.errorCodeName})", exportException)
                    setStatus(getString(R.string.export_failed))
                }
            })
            .build()
        transformer = t
        t.start(composition, out.absolutePath)
        pollProgress(t)
    }

    private fun pollProgress(t: Transformer) {
        val holder = ProgressHolder()
        main.postDelayed(object : Runnable {
            override fun run() {
                if (transformer !== t) return
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    progress.progress = holder.progress
                }
                main.postDelayed(this, 300)
            }
        }, 300)
    }

    private fun finishExport(file: File, fileLabel: String, source: Uri, lut: FloatCube) {
        transformer = null
        io.execute {
            // The check stays out of sight unless it finds the look missing from the file. The
            // numbers are for debugging, so they only go to the log.
            val unchanged = try {
                val r = verifyExport(source, file, lut)
                Log.i(TAG, r?.let { ExportVerifier.describe(it) } ?: "Export check skipped: frame sizes differ")
                r?.verdict == ExportVerifier.Verdict.UNCHANGED
            } catch (e: Exception) {
                Log.w(TAG, "Export check could not run", e)
                false
            }
            try {
                saveToMovies(file, "LUT_${fileLabel}_${System.currentTimeMillis()}.mp4")
                main.post {
                    progress.visibility = View.GONE
                    updateExportEnabled()
                    if (unchanged) setStatus(getString(R.string.export_unchanged)) else clearStatus()
                    Snackbar.make(snackbarAnchor(), R.string.export_saved, Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Save failed", e)
                main.post {
                    progress.visibility = View.GONE
                    updateExportEnabled()
                    setStatus(getString(R.string.export_save_failed))
                }
            } finally {
                file.delete()
            }
        }
    }

    // Compares one frame of the exported file with the same frame of the source, with the LUT applied to the
    // source on the CPU. Small frames keep encoder noise and resampling from drowning the comparison.
    private fun verifyExport(source: Uri, output: File, lut: FloatCube): ExportVerifier.Result? {
        val durationMs = MediaMetadataRetriever().let { r ->
            try {
                r.setDataSource(this, source)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        }
        val positionUs = if (durationMs in 1..1999) durationMs * 500 else 1_000_000L
        val src = shrink(loadBaseFrame(source, positionUs), CHECK_LONG_SIDE)
        val out = loadOutputFrame(output, positionUs)
        val aspectSrc = src.width.toFloat() / src.height
        val aspectOut = out.width.toFloat() / out.height
        if (abs(aspectSrc - aspectOut) / aspectSrc > 0.1f) return null
        val outScaled = Bitmap.createScaledBitmap(shrink(out, CHECK_LONG_SIDE * 2), src.width, src.height, true)
        val a = IntArray(src.width * src.height)
        val b = IntArray(a.size)
        src.getPixels(a, 0, src.width, 0, 0, src.width, src.height)
        outScaled.getPixels(b, 0, src.width, 0, 0, src.width, src.height)
        return ExportVerifier.check(a, b, lut)
    }

    private fun loadOutputFrame(file: File, positionUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("No frame could be read from the exported file")
        } finally {
            retriever.release()
        }
    }

    // Halves repeatedly (a cheap box-style filter) until the long side is at most longSide.
    private fun shrink(bitmap: Bitmap, longSide: Int): Bitmap {
        var b = bitmap
        while (maxOf(b.width, b.height) / 2 >= longSide) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        val side = maxOf(b.width, b.height)
        if (side <= longSide) return b
        val s = longSide.toFloat() / side
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }

    private fun loadBaseFrame(uri: Uri, positionUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val raw = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("No frame could be read at this position")
            val longSide = maxOf(raw.width, raw.height)
            if (longSide <= 1080) return raw
            val s = 1080f / longSide
            return Bitmap.createScaledBitmap(raw, (raw.width * s).roundToInt(), (raw.height * s).roundToInt(), true)
        } finally {
            retriever.release()
        }
    }

    private fun saveToMovies(file: File, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/LUT")
            }
            val target = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            contentResolver.openOutputStream(target)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            return "Movies/LUT/$name"
        }
        // API 26-28 would need WRITE_EXTERNAL_STORAGE; use app-specific storage instead.
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "LUT").apply { mkdirs() }
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private fun updateExportEnabled() {
        val ready = videoUri != null && !referenceMode && !noEffect() && diagLook() == null
        exportButton.visibility = if (ready || transformer != null) View.VISIBLE else View.GONE
        exportButton.isEnabled = ready && transformer == null
    }

    // The status line sits over the video and only appears for errors, exports and diagnostics.
    private var statusShown = false

    private fun setStatus(s: String) {
        status.text = s
        statusShown = true
        status.visibility = View.VISIBLE
    }

    private fun showEffectsOff() {
        setStatus("Preview error, LUT preview off (plain video shown). Pick a look to retry. ${effectsError ?: ""}")
    }

    private fun clearStatus() {
        statusShown = false
        status.visibility = View.GONE
    }

    private fun snackbarAnchor(): View = findViewById(android.R.id.content)

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private companion object {
        const val CHECK_LONG_SIDE = 160
        const val SPLASH_MIN_MS = 700L
        const val MAX_LAYERS = 5
        const val THUMB_LONG_SIDE = 240
        const val TAG = "LutMaster"
        // looks[0] is Rec.709 and looks[1] is Log; the real looks start here.
        const val FIRST_LOOK = 2
        const val REFERENCE_ASSET = "reference/reference-slog3.mp4"
        const val REFERENCE_PROFILE = "sony-slog3"
        // Don't rebuild a failing effects pipeline more than about once a second (strength drags).
        const val EFFECTS_RETRY_MS = 1000L
        const val SCAN_BYTES = 2 * 1024 * 1024
        const val FILTER_ALL = "\u0000all"
        const val FILTER_FAVS = "\u0000favourites"
    }
}
