package app.lutapply

import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
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
    object Original : LookItem("No look", "")
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
    private lateinit var placeholder: View
    private lateinit var strengthSlider: Slider
    private lateinit var strengthValue: TextView
    private lateinit var compareBadge: TextView
    private lateinit var backCallback: OnBackPressedCallback
    private var loadedInputCount = 0

    private val inputs = ArrayList<InputEntry>()
    private val looks = ArrayList<LookItem>()
    private val floatCache = HashMap<String, FloatCube>()
    private val composedCache = HashMap<String, Array<Array<IntArray>>>()
    private var selectedInput = 0
    private var selectedLook = 0
    private var externalCount = 0
    private var requestId = 0
    private var videoUri: Uri? = null
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
    private val composedFloatCache = HashMap<String, FloatCube>()

    private var advKeepColour = false
    private var advDiag = false
    private var fullLut = false
    private var comparing = false
    private var fullscreen = false
    private var profileUndetected = false
    private var profileDetected = false
    private var categoryFilter = FILTER_ALL
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
        placeholder = findViewById(R.id.placeholder)
        strengthSlider = findViewById(R.id.strength)
        strengthValue = findViewById(R.id.strengthValue)
        compareBadge = findViewById(R.id.compareBadge)
        playerView = findViewById(R.id.player)

        advKeepColour = prefs.getBoolean("adv_keepcolour", false)
        advDiag = prefs.getBoolean("adv_diag", false)
        favourites.addAll(prefs.getStringSet("favourites", emptySet()) ?: emptySet())
        categoryFilter = prefs.getString("filter", FILTER_ALL) ?: FILTER_ALL
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
                if (!effectsDisabled) {
                    // Retry once without effects so the video still plays.
                    effectsDisabled = true
                    val position = player.currentPosition
                    player.setVideoEffects(emptyList())
                    player.seekTo(position)
                    player.prepare()
                    player.playWhenReady = true
                    setStatus("Preview error, LUT preview off (plain video shown): $msg")
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
        }
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = setFullscreen(false)
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupInputs()
        setupLooks()
        applySelection()
        updateDebug()

        placeholder.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        strengthSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            strengths[selectedLook] = value / 100f
            strengthValue.text = "${value.roundToInt()}%"
            applySelection()
        }
        updateStrengthRow()
        exportButton.setOnClickListener { startExport() }
        if (inputs.size == 1 && looks.size == 1) setStatus("No bundled LUTs found. Use the menu to load one.")
    }

    override fun onDestroy() {
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
                R.id.menu_open -> {
                    pickVideo.launch(arrayOf("video/*"))
                    true
                }
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
        toolbar.menu.findItem(R.id.menu_keep_colour)?.isChecked = advKeepColour
        toolbar.menu.findItem(R.id.menu_show_diag)?.isChecked = advDiag
    }

    private fun openVideo(uri: Uri) {
        videoUri = uri
        firstFrame = false
        effectsDisabled = false
        placeholder.visibility = View.GONE
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
        detectProfile(uri)
        updateExportEnabled()
        applySelection()
        updateDebug()
    }

    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        for (id in intArrayOf(R.id.toolbar, R.id.strengthRow, R.id.chipScroll, R.id.looksList)) {
            findViewById<View>(id).visibility = vis
        }
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

    // A paused player only redraws on a new frame, so seek to the same position to push one through.
    private fun redrawIfPaused() {
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE || player.isPlaying) return
        val controlsShown = playerView.isControllerFullyVisible
        player.seekTo(player.currentPosition)
        if (!controlsShown) playerView.hideController()
    }

    private fun swapEffects(effects: List<Effect>) {
        val position = player.currentPosition
        val playing = player.playWhenReady
        // Changing the grade shouldn't bring up the controls. They only appear when the video is tapped.
        val controlsShown = playerView.isControllerFullyVisible
        player.setVideoEffects(effects)
        effectsApplied++
        if (player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE) {
            // A paused player only redraws on a new frame, so seek to the same position to push one through.
            if (!playing || abs(player.currentPosition - position) > 300) player.seekTo(position)
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
        val saved = prefs.getString("input", null)
        // A saved choice wins, then the bundled Samsung Log LUT, then the Rec.709 passthrough.
        selectedInput = inputs.indexOfFirst { it.key == saved }.takeIf { it >= 0 }
            ?: inputs.indexOfFirst { it.isSamsung }.takeIf { it >= 0 }
            ?: inputs.indexOfFirst { it.isPassthrough }.takeIf { it >= 0 }
            ?: 0
        profileButton.setOnClickListener { showProfilePicker() }
        updateProfileButton()
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
        val span = maxOf(3, (resources.configuration.screenWidthDp - 16) / 116)
        looksList.layoutManager = GridLayoutManager(this, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (looksAdapter.isHeader(position)) span else 1
            }
        }
        looksList.adapter = looksAdapter
        looksAdapter.selectedIndex = selectedLook
        rebuildChips()
        rebuildLooksRows()
    }

    private fun selectLook(index: Int) {
        if (comparing) endCompare()
        selectedLook = index
        updateStrengthRow()
        looksAdapter.selectedIndex = index
        updateExportEnabled()
        applySelection()
    }

    private fun toggleFavourite(key: String) {
        if (!favourites.remove(key)) favourites.add(key)
        prefs.edit().putStringSet("favourites", HashSet(favourites)).apply()
        if (categoryFilter == FILTER_FAVS) rebuildLooksRows()
    }

    private fun sortedCategories(): List<String> =
        looks.map { it.category }.filter { it.isNotEmpty() }.distinct()
            .sortedWith(compareBy({ LookCategories.sortKey(it) }, { it }))

    private fun rebuildChips() {
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        val filters = listOf(FILTER_ALL, FILTER_FAVS) + sortedCategories()
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
            rebuildLooksRows()
            looksList.scrollToPosition(0)
            regenerateThumbnails()
        }
    }

    // "No look" always comes first so the input-only result is one tap away in every view.
    private fun rebuildLooksRows() {
        fun row(i: Int) = LooksRow.Look(i, looks[i].name, looks[i].favKey)
        val rows = ArrayList<LooksRow>()
        rows.add(row(0))
        val shown = (1 until looks.size).filter { i ->
            when (categoryFilter) {
                FILTER_ALL -> true
                FILTER_FAVS -> looks[i].favKey?.let { it in favourites } == true
                else -> looks[i].category == categoryFilter
            }
        }
        if (categoryFilter == FILTER_FAVS && shown.isEmpty()) rows.add(LooksRow.Header(getString(R.string.no_favourites)))
        if (categoryFilter == FILTER_ALL || categoryFilter == FILTER_FAVS) {
            shown.groupBy { looks[it].category }
                .toSortedMap(compareBy<String>({ LookCategories.sortKey(it) }, { it }))
                .forEach { (category, indices) ->
                    rows.add(LooksRow.Header(category))
                    indices.forEach { rows.add(row(it)) }
                }
        } else {
            shown.forEach { rows.add(row(it)) }
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

    // Grades the thumbnail frame with every look, visible ones first. A newer run cancels an older one.
    private fun regenerateThumbnails() {
        val base = thumbFrame ?: return
        val gen = ++thumbGeneration
        val input = inputs[selectedInput]
        val visible = looksAdapter.visibleLookIndices()
        val visibleSet = visible.toHashSet()
        val order = (visible + looks.indices.filter { it !in visibleSet }).map { it to looks[it] }
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
            for ((index, look) in order) {
                if (gen != thumbGeneration) return@execute
                if (look is LookItem.Diag) continue
                try {
                    val second = when (look) {
                        is LookItem.Asset -> loadAsset(look.path)
                        is LookItem.External -> look.cube
                        else -> null
                    }
                    val stageOne = if (look is LookItem.External && look.full) null else first
                    val px = src.copyOf()
                    if (stageOne != null || second != null) LutMath.applyToPixels(LutMath.compose(stageOne, second, 17), px)
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

    // Builds (and caches) one 33-point LUT for the current input + look pair. Null means no effect.
    private fun resolve(onReady: (cube: Array<Array<IntArray>>?, floatCube: FloatCube?, label: String, fileLabel: String) -> Unit) {
        val input = inputs[selectedInput]
        val look = looks[selectedLook]
        val skipInput = look is LookItem.External && look.full
        val inputKey = when {
            skipInput || input.isRaw || input.isPassthrough -> null
            input.isLoaded -> "loaded-${input.id}"
            else -> input.path
        }
        val lookKey = when (look) {
            is LookItem.Original -> "original"
            is LookItem.Asset -> look.path
            is LookItem.External -> "external-${look.id}"
            is LookItem.Diag -> "diag-${look.kind}"
        }
        val strength = if (look is LookItem.Original) 1f else lookStrength()
        val parts = ArrayList<String>()
        if (!skipInput) parts.add(input.name)
        if (look !is LookItem.Original) parts.add(if (strength < 1f) "${look.name} ${(strength * 100).roundToInt()}%" else look.name)
        if (parts.isEmpty()) parts.add("Raw")
        val label = parts.joinToString(" + ")
        val fileLabel = parts.joinToString("_") { LutNames.slug(it) }
        val key = "${inputKey ?: "raw"}|$lookKey"
        val baseKey = "${inputKey ?: "raw"}|original"
        val request = ++requestId

        if (inputKey == null && look is LookItem.Original) {
            onReady(null, null, label, fileLabel)
            return
        }

        // Strength blends the profile on its own (base) with the profile plus the full look.
        fun deliver(full: FloatCube, base: FloatCube) {
            if (strength >= 0.999f) {
                val packed = composedCache.getOrPut(key) { CubeParser.pack(full) }
                onReady(packed, full, label, fileLabel)
            } else {
                val mixed = LutMath.mix(base, full, strength)
                onReady(CubeParser.pack(mixed), mixed, label, fileLabel)
            }
        }

        val cachedFull = composedFloatCache[key]
        val cachedBase = composedFloatCache[baseKey]
        if (cachedFull != null && cachedBase != null) {
            deliver(cachedFull, cachedBase)
            return
        }
        io.execute {
            try {
                val first = when {
                    inputKey == null -> null
                    input.isLoaded -> input.cube
                    else -> loadAsset(input.path!!)
                }
                val second = when (look) {
                    is LookItem.Original -> null
                    is LookItem.Asset -> loadAsset(look.path)
                    is LookItem.External -> look.cube
                    is LookItem.Diag -> null
                }
                val full = cachedFull ?: LutMath.compose(first, second, 33)
                val base = cachedBase ?: LutMath.compose(first, null, 33)
                main.post {
                    composedFloatCache[key] = full
                    composedFloatCache[baseKey] = base
                    if (request == requestId) deliver(full, base)
                }
            } catch (e: Exception) {
                main.post { if (request == requestId) setStatus("LUT error ($label): ${e.message}") }
            }
        }
    }

    // Strength for the selected look, remembered per look for this session.
    private val strengths = HashMap<Int, Float>()

    private fun lookStrength(): Float = strengths[selectedLook] ?: 1f

    private fun updateStrengthRow() {
        val look = looks[selectedLook]
        val adjustable = look !is LookItem.Original && look !is LookItem.Diag
        val pct = (lookStrength() * 100).roundToInt()
        strengthSlider.value = pct.toFloat()
        strengthSlider.isEnabled = adjustable
        strengthValue.text = if (adjustable) "$pct%" else "–"
    }

    // Choosing an input or a look applies it to the playing or paused video straight away.
    private fun applySelection() {
        val look = looks[selectedLook]
        if (look is LookItem.Diag) {
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
            clearStatus()
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
        return looks[selectedLook] is LookItem.Original && (input.isRaw || input.isPassthrough)
    }

    private fun describeSelection(): String {
        val input = inputs[selectedInput]
        val look = looks[selectedLook]
        if (noEffect()) {
            return if (input.isRaw) "Raw footage, no transform" else "No effect (Rec.709 passthrough, no look)"
        }
        val inputText = when {
            look is LookItem.External && look.full -> "skipped (full LUT)"
            input.isSlog3 -> "${input.name} (expects S-Log3 footage)"
            else -> input.name
        }
        return "Camera: $inputText + Look: ${look.name}"
    }

    // An identity LUT stands in for "no effect" so the effects pipeline stays set up on the player.
    private fun applyLut(cube: Array<Array<IntArray>>?) {
        lookCube = cube
        if (!comparing) liveLut.set(cube ?: identityCube)
        if (currentEffects !== lutEffects) {
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
                    if (selectedLook >= at) selectedLook++
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
        if (looks[selectedLook] is LookItem.Diag) return
        resolve { cube, floatCube, label, fileLabel ->
            if (cube != null && floatCube != null) runExport(uri, label, fileLabel, cube, floatCube)
        }
    }

    private fun runExport(uri: Uri, label: String, fileLabel: String, cube: Array<Array<IntArray>>, lut: FloatCube) {
        exportButton.isEnabled = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        setStatus("Exporting with $label...")

        val out = File(cacheDir, "graded_${System.currentTimeMillis()}.mp4")
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), listOf<Effect>(SingleColorLut.createFromCube(cube))))
            .build()
        // Ignore HDR colour flags so the LUT acts on the raw code values, matching the preview.
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
            .build()

        val t = Transformer.Builder(this)
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
                    setStatus("Export failed: ${exportException.message} (${exportException.errorCodeName})")
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
        setStatus("Checking the export...")
        io.execute {
            val verdict = try {
                verifyExport(source, file, lut)
            } catch (e: Exception) {
                "Export check could not run: ${e.message ?: e.javaClass.simpleName}"
            }
            try {
                val where = saveToMovies(file, "LUT_${fileLabel}_${System.currentTimeMillis()}.mp4")
                main.post {
                    progress.visibility = View.GONE
                    updateExportEnabled()
                    setStatus(verdict)
                    Snackbar.make(snackbarAnchor(), "Saved: $where", Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                main.post {
                    progress.visibility = View.GONE
                    updateExportEnabled()
                    setStatus("Save failed: ${e.message}\n$verdict")
                }
            } finally {
                file.delete()
            }
        }
    }

    // Compares one frame of the exported file with the same frame of the source, with the LUT applied to the
    // source on the CPU. Small frames keep encoder noise and resampling from drowning the comparison.
    private fun verifyExport(source: Uri, output: File, lut: FloatCube): String {
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
        if (abs(aspectSrc - aspectOut) / aspectSrc > 0.1f) {
            return "Export check inconclusive: the exported frame is ${out.width}x${out.height} but the source is ${src.width}x${src.height}"
        }
        val outScaled = Bitmap.createScaledBitmap(shrink(out, CHECK_LONG_SIDE * 2), src.width, src.height, true)
        val a = IntArray(src.width * src.height)
        val b = IntArray(a.size)
        src.getPixels(a, 0, src.width, 0, 0, src.width, src.height)
        outScaled.getPixels(b, 0, src.width, 0, 0, src.width, src.height)
        return ExportVerifier.describe(ExportVerifier.check(a, b, lut))
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
        val ready = videoUri != null && !noEffect() && looks[selectedLook] !is LookItem.Diag
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
        const val THUMB_LONG_SIDE = 240
        const val SCAN_BYTES = 2 * 1024 * 1024
        const val FILTER_ALL = "\u0000all"
        const val FILTER_FAVS = "\u0000favourites"
    }
}
