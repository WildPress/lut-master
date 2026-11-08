package app.lutapply

import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

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

private sealed class LookItem(val name: String) {
    object Original : LookItem("Original (input only)")
    class Asset(name: String, val path: String) : LookItem(name)
    class External(name: String, val cube: FloatCube, val full: Boolean, val id: Int) : LookItem(name)
    class Diag(name: String, val kind: Int) : LookItem(name)
}

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var exportButton: MaterialButton
    private lateinit var looksList: RecyclerView
    private lateinit var looksAdapter: LooksAdapter
    private lateinit var inputDropdown: AutoCompleteTextView
    private lateinit var inputLayout: TextInputLayout
    private lateinit var removeInputButton: MaterialButton
    private lateinit var placeholder: TextView
    private lateinit var compareBadge: TextView
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
    private val prefs by lazy { getSharedPreferences("lutapply", MODE_PRIVATE) }

    private var currentCube: Array<Array<IntArray>>? = null
    private var currentEffects: List<Effect> = emptyList()
    private var diagMode = 0
    private var effectsApplied = 0
    private var firstFrame = false
    private var errorCount = 0
    private var effectsDisabled = false
    private val identityCube by lazy { CubeParser.pack(LutMath.compose(null, null, 17)) }
    private val composedFloatCache = HashMap<String, FloatCube>()

    private var advKeepColour = false
    private var advDiag = false
    private var fullLut = false
    private var comparing = false
    private var thumbnailFrame: Bitmap? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            videoUri = uri
            firstFrame = false
            effectsDisabled = false
            placeholder.visibility = View.GONE
            player.setVideoEffects(currentEffects)
            effectsApplied++
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
            updateExportEnabled()
            applySelection()
            updateDebug()
            thumbnailFrame = null
            main.postDelayed({ captureThumbnailFrame() }, 500)
        }
    }

    private val pickLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadExternalLut(uri)
    }

    private val pickInputLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadInputLut(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        debug = findViewById(R.id.debug)
        removeInputButton = findViewById(R.id.removeInput)
        progress = findViewById(R.id.progress)
        exportButton = findViewById(R.id.export)
        looksList = findViewById(R.id.looksList)
        inputDropdown = findViewById(R.id.inputDropdown)
        inputLayout = findViewById(R.id.inputLayout)
        placeholder = findViewById(R.id.placeholder)
        compareBadge = findViewById(R.id.compareBadge)

        advKeepColour = prefs.getBoolean("adv_keepcolour", false)
        advDiag = prefs.getBoolean("adv_diag", false)

        setupToolbar()

        val baseExtractors = DefaultExtractorsFactory()
        val extractors: ExtractorsFactory = if (advKeepColour) baseExtractors else StripColorInfoExtractorsFactory(baseExtractors)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .build()
        currentEffects = effectList(null)
        player.setVideoEffects(currentEffects)
        effectsApplied++
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
                updateDebug()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) = updateDebug()

            override fun onTracksChanged(tracks: Tracks) = updateDebug()

            override fun onPlayerError(error: PlaybackException) {
                errorCount++
                val cause = error.cause?.let { " / ${it.javaClass.simpleName}: ${it.message}" } ?: ""
                val msg = "${error.errorCodeName}: ${error.message}$cause"
                if (!effectsDisabled) {
                    effectsDisabled = true
                    val position = player.currentPosition
                    player.setVideoEffects(emptyList())
                    player.seekTo(position)
                    player.prepare()
                    player.playWhenReady = true
                    setStatus("Preview error, LUT preview off: $msg")
                } else {
                    setStatus("Playback error: $msg")
                }
                updateDebug()
            }
        })
        findViewById<PlayerView>(R.id.player).player = player

        val controls = findViewById<PlayerControlView>(R.id.controls)
        controls.player = player
        controls.setShowTimeoutMs(0)
        controls.show()

        setupTapAndCompare()
        setupInputs()
        setupLooks()
        applySelection()
        updateDebug()

        findViewById<MaterialButton>(R.id.pickVideo).setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        exportButton.setOnClickListener { startExport() }
        if (inputs.size == 1 && looks.size == 1) setStatus("No bundled LUTs found. Use the menu to load one.")
    }

    override fun onDestroy() {
        transformer?.cancel()
        player.release()
        io.shutdown()
        super.onDestroy()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_load_look -> { pickLut.launch(arrayOf("*/*")); true }
                R.id.menu_load_input -> { pickInputLut.launch(arrayOf("*/*")); true }
                R.id.menu_full_lut -> {
                    fullLut = !fullLut
                    item.isChecked = fullLut
                    true
                }
                R.id.menu_keep_colour -> {
                    prefs.edit().putBoolean("adv_keepcolour", !item.isChecked).apply()
                    recreate()
                    true
                }
                R.id.menu_show_diag -> {
                    prefs.edit().putBoolean("adv_diag", !item.isChecked).apply()
                    recreate()
                    true
                }
                else -> false
            }
        }
        toolbar.menu.findItem(R.id.menu_keep_colour)?.isChecked = advKeepColour
        toolbar.menu.findItem(R.id.menu_show_diag)?.isChecked = advDiag
    }

    private fun setupTapAndCompare() {
        val tapLayer = findViewById<View>(R.id.tapLayer)
        tapLayer.setOnClickListener { togglePlayPause() }
        tapLayer.setOnLongClickListener {
            if (videoUri != null && !effectsDisabled) {
                startCompare()
            }
            true
        }
        tapLayer.setOnTouchListener { _, event ->
            if (comparing && event.action == MotionEvent.ACTION_UP) {
                endCompare()
            }
            false
        }
    }

    private fun startCompare() {
        comparing = true
        compareBadge.visibility = View.VISIBLE
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.setVideoEffects(effectList(null))
        if (player.mediaItemCount > 0) player.seekTo(position)
        player.playWhenReady = playing
    }

    private fun endCompare() {
        comparing = false
        compareBadge.visibility = View.GONE
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.setVideoEffects(currentEffects)
        if (player.mediaItemCount > 0) player.seekTo(position)
        player.playWhenReady = playing
    }

    private fun togglePlayPause() {
        if (player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.playWhenReady) {
            player.pause()
        } else {
            player.play()
        }
    }

    private fun assetCubes(dir: String): List<String> =
        (assets.list(dir) ?: emptyArray()).filter { it.endsWith(".cube", ignoreCase = true) }.sorted()

    private fun setupInputs() {
        assetCubes("luts/input").forEach { inputs.add(InputEntry(LutNames.pretty(it), "luts/input/$it")) }
        storedInputFiles().forEach { f ->
            try {
                val cube = CubeParser.parseFloat(f.readText())
                inputs.add(InputEntry(LutNames.pretty(f.name) + " (loaded)", null, cube, ++loadedInputCount, f))
            } catch (_: Exception) {
                f.delete()
            }
        }
        inputs.add(InputEntry("Raw (no transform)", null))
        val saved = prefs.getString("input", null)
        selectedInput = inputs.indexOfFirst { it.key == saved }.takeIf { it >= 0 }
            ?: inputs.indexOfFirst { it.isSamsung }.takeIf { it >= 0 }
            ?: inputs.indexOfFirst { it.isPassthrough }.takeIf { it >= 0 }
            ?: 0

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, inputs.map { it.name })
        inputDropdown.setAdapter(adapter)
        inputDropdown.setText(inputs[selectedInput].name, false)
        inputDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == selectedInput) return@setOnItemClickListener
            selectedInput = position
            prefs.edit().putString("input", inputs[position].key).apply()
            onInputChanged()
        }
        removeInputButton.setOnClickListener { confirmRemoveInput() }
        updateRemoveButton()
    }

    private fun onInputChanged() {
        updateRemoveButton()
        updateExportEnabled()
        applySelection()
        regenerateThumbnails()
    }

    private fun updateRemoveButton() {
        removeInputButton.visibility = if (inputs[selectedInput].isLoaded) View.VISIBLE else View.GONE
    }

    private fun storedInputFiles(): List<File> =
        File(filesDir, "inputs").listFiles { f -> f.isFile && f.name.endsWith(".cube") }?.sortedBy { it.name } ?: emptyList()

    private fun confirmRemoveInput() {
        val entry = inputs[selectedInput]
        if (!entry.isLoaded) return
        AlertDialog.Builder(this)
            .setMessage("Remove ${entry.name} from this app? You can load it again from the file.")
            .setPositiveButton("Remove") { _, _ -> removeInput(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeInput(entry: InputEntry) {
        entry.file?.delete()
        inputs.remove(entry)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, inputs.map { it.name })
        inputDropdown.setAdapter(adapter)
        selectedInput = inputs.indexOfFirst { it.isPassthrough }.takeIf { it >= 0 } ?: 0
        prefs.edit().putString("input", inputs[selectedInput].key).apply()
        inputDropdown.setText(inputs[selectedInput].name, false)
        onInputChanged()
    }

    private fun setupLooks() {
        looks.add(LookItem.Original)
        assetCubes("luts/looks").forEach { looks.add(LookItem.Asset(LutNames.pretty(it), "luts/looks/$it")) }
        if (advDiag) {
            looks.add(LookItem.Diag("DIAG 1: swap colour channels (RgbMatrix)", 1))
            looks.add(LookItem.Diag("DIAG 2: invert colours (SingleColorLut)", 2))
        }

        looksAdapter = LooksAdapter { index ->
            selectedLook = index
            looksAdapter.selectedIndex = index
            updateExportEnabled()
            applySelection()
        }
        looksList.layoutManager = LinearLayoutManager(this)
        looksList.adapter = looksAdapter
        looksAdapter.selectedIndex = 0
        rebuildLooksRows()
    }

    private fun rebuildLooksRows() {
        val rows = ArrayList<LooksRow>()
        val grouped = LinkedHashMap<String, ArrayList<Pair<Int, LookItem>>>()

        for ((index, look) in looks.withIndex()) {
            val group = when (look) {
                is LookItem.Original -> "Original"
                is LookItem.Asset -> LookCategories.categorise(look.path)
                is LookItem.External -> "External"
                is LookItem.Diag -> "Diagnostics"
            }
            grouped.getOrPut(group) { ArrayList() }.add(index to look)
        }

        val sortedGroups = grouped.entries.sortedBy { LookCategories.groupOrder(it.key) }
        for ((group, items) in sortedGroups) {
            if (group != "Original") {
                rows.add(LooksRow.Header(group))
            }
            for ((index, look) in items) {
                rows.add(LooksRow.Look(index, look.name))
            }
        }
        looksAdapter.rows = rows
    }

    private fun loadAsset(path: String): FloatCube =
        synchronized(floatCache) { floatCache[path] }
            ?: CubeParser.parseFloat(assets.open(path).bufferedReader().use { it.readText() }).also {
                synchronized(floatCache) { floatCache[path] = it }
            }

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
        val parts = ArrayList<String>()
        if (!skipInput) parts.add(input.name)
        if (look !is LookItem.Original) parts.add(look.name)
        if (parts.isEmpty()) parts.add("Raw")
        val label = parts.joinToString(" + ")
        val fileLabel = parts.joinToString("_") { LutNames.slug(it) }
        val key = "${inputKey ?: "raw"}|$lookKey"
        val request = ++requestId

        if (inputKey == null && look is LookItem.Original) {
            onReady(null, null, label, fileLabel)
            return
        }
        composedCache[key]?.let { onReady(it, composedFloatCache[key], label, fileLabel); return }
        setStatus("Preparing $label...")
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
                val composedFloat = LutMath.compose(first, second, 33)
                val composed = CubeParser.pack(composedFloat)
                main.post {
                    composedCache[key] = composed
                    composedFloatCache[key] = composedFloat
                    if (request == requestId) onReady(composed, composedFloat, label, fileLabel)
                }
            } catch (e: Exception) {
                main.post { if (request == requestId) setStatus("LUT error ($label): ${e.message}") }
            }
        }
    }

    private fun applySelection() {
        val look = looks[selectedLook]
        if (look is LookItem.Diag) {
            requestId++
            currentCube = null
            diagMode = look.kind
            applyEffects(diagEffects(look.kind))
            setStatus("${look.name}: bypasses the input and every LUT.")
            return
        }
        diagMode = 0
        resolve { cube, _, _, _ ->
            currentCube = cube
            applyEffects(effectList(cube))
            setStatus(describeSelection())
        }
    }

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
            return if (input.isRaw) "Raw footage, no transform" else "No effect (Rec.709 passthrough)"
        }
        val inputText = when {
            look is LookItem.External && look.full -> "skipped (full LUT)"
            input.isSlog3 -> "${input.name} (expects S-Log3 footage)"
            else -> input.name
        }
        return "Input: $inputText + Look: ${look.name}"
    }

    private fun effectList(cube: Array<Array<IntArray>>?): List<Effect> =
        listOf(SingleColorLut.createFromCube(cube ?: identityCube))

    private fun applyEffects(effects: List<Effect>) {
        currentEffects = effects
        if (effectsDisabled || comparing) {
            updateDebug()
            return
        }
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.setVideoEffects(effects)
        effectsApplied++
        if (player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE) {
            if (!playing || abs(player.currentPosition - position) > 300) player.seekTo(position)
        }
        player.playWhenReady = playing
        updateDebug()
    }

    private fun updateDebug() {
        val f = player.videoFormat
        val src = if (f != null) "${f.width}x${f.height} | ${f.sampleMimeType}" else "no video"
        val mode = when {
            effectsDisabled -> "OFF after error"
            diagMode != 0 -> "DIAG $diagMode"
            currentCube == null -> "identity"
            else -> "LUT"
        }
        debug.text = "effects: $effectsApplied ($mode) | $src | frame: ${if (firstFrame) "yes" else "no"} | errors: $errorCount"
    }

    private fun loadInputLut(uri: Uri) {
        setStatus("Reading input LUT...")
        io.execute {
            try {
                val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val cube = CubeParser.parseFloat(text)
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
                    val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, inputs.map { it.name })
                    inputDropdown.setAdapter(adapter)
                    prefs.edit().putString("input", entry.key).apply()
                    inputDropdown.setText(inputs[selectedInput].name, false)
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
                    val at = looks.indexOfFirst { it is LookItem.Diag }.let { if (it < 0) looks.size else it }
                    looks.add(at, LookItem.External(name, cube, full, ++externalCount))
                    selectedLook = at
                    looksAdapter.selectedIndex = selectedLook
                    rebuildLooksRows()
                    updateExportEnabled()
                    applySelection()
                }
            } catch (e: Exception) {
                main.post { setStatus("LUT error: ${e.message}") }
            }
        }
    }

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
                    setStatus("Export failed: ${exportException.message}")
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
                    Snackbar.make(looksList, "Saved: $where", Snackbar.LENGTH_LONG).show()
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
            return "Export check inconclusive: frame size mismatch (${out.width}x${out.height} vs ${src.width}x${src.height})"
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
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "LUT").apply { mkdirs() }
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private fun updateExportEnabled() {
        exportButton.isEnabled = videoUri != null && !noEffect() && transformer == null && looks[selectedLook] !is LookItem.Diag
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun captureThumbnailFrame() {
        val uri = videoUri ?: return
        io.execute {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val frame = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                retriever.release()
                if (frame != null) {
                    val thumb = shrink(frame, 96)
                    main.post {
                        thumbnailFrame = thumb
                        regenerateThumbnails()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun regenerateThumbnails() {
        val base = thumbnailFrame ?: return
        io.execute {
            for ((index, look) in looks.withIndex()) {
                if (look is LookItem.Original || look is LookItem.Diag) continue
                try {
                    val input = inputs[selectedInput]
                    val skipInput = look is LookItem.External && look.full
                    val first = when {
                        skipInput || input.isRaw || input.isPassthrough -> null
                        input.isLoaded -> input.cube
                        input.path != null -> loadAsset(input.path)
                        else -> null
                    }
                    val second = when (look) {
                        is LookItem.Asset -> loadAsset(look.path)
                        is LookItem.External -> look.cube
                        else -> null
                    }
                    val composed = LutMath.compose(first, second, 17)
                    val copy = base.copy(Bitmap.Config.ARGB_8888, true)
                    val pixels = IntArray(copy.width * copy.height)
                    copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
                    LutMath.applyToPixels(composed, pixels)
                    copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
                    main.post { looksAdapter.updateThumbnail(index, copy) }
                } catch (_: Exception) {}
            }
        }
    }

    private companion object {
        const val CHECK_LONG_SIDE = 160
    }
}
