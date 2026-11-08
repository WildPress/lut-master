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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
import java.io.File
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

private sealed class LookItem(val name: String) {
    object Original : LookItem("Original (input only)")
    class Asset(name: String, val path: String) : LookItem(name)
    class External(name: String, val cube: FloatCube, val full: Boolean, val id: Int) : LookItem(name)
    // Diagnostic effects that bypass the input transform and every LUT file.
    class Diag(name: String, val kind: Int) : LookItem(name)
}

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var progress: ProgressBar
    private lateinit var exportButton: Button
    private lateinit var list: ListView
    private lateinit var inputSpinner: Spinner
    private lateinit var fullLut: CheckBox
    private lateinit var lookAdapter: ArrayAdapter<String>
    private lateinit var inputAdapter: ArrayAdapter<String>
    private lateinit var removeInputButton: Button
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

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            videoUri = uri
            firstFrame = false
            effectsDisabled = false
            // Media3 requires setVideoEffects at least once before prepare() to set up the effects pipeline.
            player.setVideoEffects(currentEffects)
            effectsApplied++
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
            updateExportEnabled()
            applySelection()
            updateDebug()
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
        list = findViewById(R.id.lutList)
        inputSpinner = findViewById(R.id.inputSpinner)
        fullLut = findViewById(R.id.fullLut)

        advKeepColour = prefs.getBoolean("adv_keepcolour", false)
        advDiag = prefs.getBoolean("adv_diag", false)

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
        findViewById<PlayerView>(R.id.player).player = player

        // Controls sit below the picture so tapping the video never covers it.
        val controls = findViewById<PlayerControlView>(R.id.controls)
        controls.player = player
        controls.setShowTimeoutMs(0)
        controls.show()
        findViewById<View>(R.id.tapLayer).setOnClickListener { togglePlayPause() }

        setupInputs()
        setupLooks()
        applySelection()
        updateDebug()
        setupAdvanced()

        findViewById<Button>(R.id.pickVideo).setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        findViewById<Button>(R.id.pickLut).setOnClickListener { pickLut.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.pickInputLut).setOnClickListener { pickInputLut.launch(arrayOf("*/*")) }
        exportButton.setOnClickListener { startExport() }
        if (inputs.size == 1 && looks.size == 1) setStatus("No bundled LUTs found. Use Load .cube... to add one.")
    }

    override fun onDestroy() {
        transformer?.cancel()
        player.release()
        io.shutdown()
        super.onDestroy()
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

    private fun setupAdvanced() {
        val panel = findViewById<LinearLayout>(R.id.advancedPanel)
        findViewById<Button>(R.id.advancedButton).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        bindAdvanced(R.id.advKeepColour, "adv_keepcolour", advKeepColour)
        bindAdvanced(R.id.advDiag, "adv_diag", advDiag)
    }

    // The player is built once per screen, so a changed option takes effect by recreating the activity.
    private fun bindAdvanced(id: Int, key: String, initial: Boolean) {
        findViewById<CheckBox>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                recreate()
            }
        }
    }

    private fun assetCubes(dir: String): List<String> =
        (assets.list(dir) ?: emptyArray()).filter { it.endsWith(".cube", ignoreCase = true) }.sorted()

    private fun setupInputs() {
        assetCubes("luts/input").forEach { inputs.add(InputEntry(LutNames.pretty(it), "luts/input/$it")) }
        // Input LUTs the user loaded earlier (kept in app-private storage, never bundled).
        storedInputFiles().forEach { f ->
            try {
                val cube = CubeParser.parseFloat(f.readText())
                inputs.add(InputEntry(LutNames.pretty(f.name) + " (loaded)", null, cube, ++loadedInputCount, f))
            } catch (e: Exception) {
                // A stored file that no longer parses is skipped; the Remove button cannot reach it, so delete it.
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
        inputAdapter = ArrayAdapter(this, R.layout.item_spinner, inputs.map { it.name }.toMutableList())
        inputSpinner.adapter = inputAdapter
        inputSpinner.setSelection(selectedInput, false)
        inputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == selectedInput) return
                selectedInput = position
                prefs.edit().putString("input", inputs[position].key).apply()
                onInputChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        removeInputButton.setOnClickListener { confirmRemoveInput() }
        updateRemoveButton()
    }

    private fun onInputChanged() {
        updateRemoveButton()
        updateExportEnabled()
        applySelection()
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
        inputAdapter.clear()
        inputAdapter.addAll(inputs.map { it.name })
        selectedInput = inputs.indexOfFirst { it.isPassthrough }.takeIf { it >= 0 } ?: 0
        prefs.edit().putString("input", inputs[selectedInput].key).apply()
        inputSpinner.setSelection(selectedInput, false)
        onInputChanged()
    }

    private fun setupLooks() {
        looks.add(LookItem.Original)
        assetCubes("luts/looks").forEach { looks.add(LookItem.Asset(LutNames.pretty(it), "luts/looks/$it")) }
        if (advDiag) {
            looks.add(LookItem.Diag("DIAG 1: swap colour channels (RgbMatrix)", 1))
            looks.add(LookItem.Diag("DIAG 2: invert colours (SingleColorLut)", 2))
        }
        lookAdapter = ArrayAdapter(this, R.layout.item_lut, looks.map { it.name }.toMutableList())
        list.adapter = lookAdapter
        list.setItemChecked(0, true)
        list.setOnItemClickListener { _, _, position, _ ->
            selectedLook = position
            list.setItemChecked(position, true)
            updateExportEnabled()
            applySelection()
        }
    }

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
            applyEffects(effectList(cube))
            setStatus(describeSelection())
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
            return if (input.isRaw) "Raw footage, no transform" else "No effect (Rec.709 passthrough, Original)"
        }
        val inputText = when {
            look is LookItem.External && look.full -> "skipped (full LUT)"
            input.isSlog3 -> "${input.name} (expects S-Log3 footage)"
            else -> input.name
        }
        return "Input: $inputText + Look: ${look.name}"
    }

    // An identity LUT stands in for "no effect" so the effects pipeline stays set up on the player.
    private fun effectList(cube: Array<Array<IntArray>>?): List<Effect> =
        listOf(SingleColorLut.createFromCube(cube ?: identityCube))

    private fun applyEffects(effects: List<Effect>) {
        currentEffects = effects
        if (effectsDisabled) {
            updateDebug()
            return
        }
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.setVideoEffects(effects)
        effectsApplied++
        if (player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE) {
            // A paused player only redraws on a new frame, so seek to the same position to push one through the new LUT.
            if (!playing || abs(player.currentPosition - position) > 300) player.seekTo(position)
        }
        player.playWhenReady = playing
        updateDebug()
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
                        inputAdapter.insert(name, at)
                        selectedInput = at
                    }
                    prefs.edit().putString("input", entry.key).apply()
                    inputSpinner.setSelection(selectedInput, false)
                    onInputChanged()
                }
            } catch (e: Exception) {
                main.post { setStatus("Input LUT error: ${e.message}") }
            }
        }
    }

    private fun loadExternalLut(uri: Uri) {
        val full = fullLut.isChecked
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
                    lookAdapter.insert(name, at)
                    selectedLook = at
                    list.setItemChecked(selectedLook, true)
                    list.smoothScrollToPosition(selectedLook)
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
                    setStatus("Saved: $where\n$verdict")
                    Toast.makeText(this, "Saved: $where", Toast.LENGTH_LONG).show()
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
        exportButton.isEnabled = videoUri != null && !noEffect() && transformer == null && looks[selectedLook] !is LookItem.Diag
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private companion object {
        const val CHECK_LONG_SIDE = 160
    }
}
