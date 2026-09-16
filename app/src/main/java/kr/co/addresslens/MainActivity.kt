package kr.co.addresslens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.widget.doAfterTextChanged
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kr.co.addresslens.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var dictionaryExecutor: ExecutorService
    private lateinit var converter: AddressConverter
    private lateinit var ocrProcessor: OcrRetryProcessor
    private var imageCapture: ImageCapture? = null
    @Volatile private var frozenFrame = false
    @Volatile private var capturePending = false
    @Volatile private var editingAddress = false
    private var developerMode = false
    private var diagnosticBitmap: Bitmap? = null
    private var lastRetryAt = 0L
    private var diagnosticArea = ""
    private var diagnosticExtraction = ""
    private var diagnosticSearch = ""
    private var diagnosticStage = ""
    private var lastSubmittedKey: String? = null
    private val apiAlternatives = mutableMapOf<String, AddressResult>()
    private var frameSequence = 0L
    private lateinit var networkAvailability: NetworkAvailability
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val processingFrame = AtomicBoolean(false)
    private val candidateTracker = CandidateTracker()
    private val autoConversion = AutoConversionPolicy()
    private val candidateInputLock = Any()

    @Volatile private var candidateEngine: AddressCandidateEngine? = null
    @Volatile private var dictionaryReady = false
    @Volatile private var scannerPaused = false
    private var camera: Camera? = null
    private var lastAnalysisAt = 0L
    private var automaticUpdateCheckStarted = false
    private val conversionRequests = ConversionRequestGate()
    private var awaitingNetwork = false
    private var internetAvailable = true
    private var currentMapAddress: String? = null
    private var currentRegion = RegionSelection()
    private var showCandidateList = false
    private var continuousScan = false
    private var selectedCandidate: AddressCandidate? = null
    private var displayedCandidates = emptyList<AddressCandidate>()
    private var renderedCandidateKey = ""
    private var candidateTouchActive = false
    private var nextSignature: String? = null
    private var nextCount = 0
    private var lastOcrSourceText = ""
    private var lastReconstructedText = ""
    private var pendingCandidateInput: CandidateInput? = null
    private var candidateWorkerRunning = false
    private var candidateGeneration = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        showPermissionPanel(!granted)
        if (granted) startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        applySystemInsets()

        cameraExecutor = Executors.newSingleThreadExecutor()
        dictionaryExecutor = Executors.newSingleThreadExecutor()
        ocrProcessor = OcrRetryProcessor(recognizer, cameraExecutor)
        networkAvailability = NetworkAvailability(this)
        internetAvailable = NetworkAvailability.isOnline(this)
        ApiSettingsStore.migrate(this)
        currentRegion = ApiSettingsStore.loadRegion(this)
        val credentials = ApiSettingsStore.load(this)
        converter = AddressConverter(
            credentials.vworldApiKey,
            credentials.naverClientId,
            credentials.naverClientSecret,
            credentials.kakaoRestApiKey,
            hasInternet = { NetworkAvailability.isOnline(applicationContext) }
        )
        configureActions()
        loadDictionary()
        applyScannerPreferences()
        requestCameraIfNeeded()
        configureKeyboardBackHandling()
        initializeDictionaryUpdate()
    }

    private fun initializeDictionaryUpdate() {
        DictionaryUpdater.initializeOnce(this) { result ->
            if (result is DictionaryUpdateResult.Updated) runOnUiThread {
                if (!isFinishing && !isDestroyed) loadDictionary()
            }
        }
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = ContextCompat.getColor(this, R.color.navy)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.navy)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun loadDictionary() {
        dictionaryReady = false
        dictionaryExecutor.execute {
            try {
                candidateEngine = AddressCandidateEngine(AddressDictionary.get(this))
                dictionaryReady = true
            } catch (_: Exception) {
                candidateEngine = null
                dictionaryReady = false
                runOnUiThread {
                    Toast.makeText(this, R.string.dictionary_load_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun configureActions() = with(binding) {
        freezeButton.setOnClickListener { hideKeyboard(); freezeCameraFrame() }
        recognizeSelectionButton.setOnClickListener { hideKeyboard(); recognizeFrozenSelection() }
        resumeCameraButton.setOnClickListener { hideKeyboard(); resumeCameraFrame() }
        resetRecognitionButton.setOnClickListener { hideKeyboard(); resumeScanning(clearResult = true) }
        frozenSelection.onSelectionChanged = {
            if (frozenFrame) {
                invalidateCandidateWork()
                conversionRequests.invalidate()
                selectedCandidate = null
                lastSubmittedKey = null
                apiAlternatives.clear()
                awaitingNetwork = false
                candidateTracker.clear()
                renderCandidates(emptyList())
                addressInput.text?.clear()
                detailText.text = ""; detailText.isVisible = false
                lastOcrSourceText = ""; diagnosticExtraction = ""; diagnosticSearch = ""; diagnosticStage = "영역 변경"
                clearDiagnosticImage()
                updateDiagnostics()
                currentMapAddress = null
                mapButton.isEnabled = false
                convertButton.isEnabled = true
                roadAddressText.setText(R.string.waiting_road)
                statusText.setText(R.string.selection_changed)
            }
        }
        addressInput.setOnFocusChangeListener { _, focused ->
            if (focused) {
                editingAddress = true
                invalidateCandidateWork()
                conversionRequests.invalidate()
                convertButton.isEnabled = true
            }
        }
        addressInput.doAfterTextChanged {
            if (addressInput.hasFocus()) {
                editingAddress = true
                invalidateCandidateWork()
                conversionRequests.invalidate()
                currentMapAddress = null
                mapButton.isEnabled = false
                convertButton.isEnabled = true
            }
        }
        permissionButton.setOnClickListener { permissionLauncher.launch(Manifest.permission.CAMERA) }
        convertButton.setOnClickListener {
            hideKeyboard()
            submitManualAddress()
        }
        addressInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                submitManualAddress()
                true
            } else false
        }
        apiKeyButton.setOnClickListener { hideKeyboard(); openSettings() }
        regionButton.setOnClickListener { openRegionSettings() }
        scanAgainButton.setOnClickListener {
            hideKeyboard()
            if (frozenFrame) recognizeFrozenSelection()
            else if (continuousScan) togglePause() else resumeScanning(clearResult = true)
        }
        mapButton.setOnClickListener { hideKeyboard(); openCurrentAddressInMap(forceChooser = false) }
        mapButton.setOnLongClickListener {
            hideKeyboard()
            openCurrentAddressInMap(forceChooser = true)
            true
        }
        flashButton.setOnClickListener {
            val info = camera?.cameraInfo ?: return@setOnClickListener
            if (!info.hasFlashUnit()) return@setOnClickListener
            camera?.cameraControl?.enableTorch(info.torchState.value != 1)
        }
    }

    private fun configureKeyboardBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    hideKeyboard(clearFocus = true)
                } else if (frozenFrame || capturePending) {
                    resumeCameraFrame()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val bounds = Rect()
            candidateTouchActive = binding.candidateContainer.getGlobalVisibleRect(bounds) &&
                bounds.contains(event.rawX.toInt(), event.rawY.toInt())
        }
        if (event.action == MotionEvent.ACTION_DOWN && currentFocus === binding.addressInput) {
            val bounds = Rect()
            binding.addressInput.getGlobalVisibleRect(bounds)
            if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt())) hideKeyboard(clearFocus = true)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            candidateTouchActive = false
        }
        return handled
    }

    private fun hideKeyboard(clearFocus: Boolean = true) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.addressInput.windowToken, 0)
        if (clearFocus) binding.addressInput.clearFocus()
    }

    private fun freezeCameraFrame() {
        val capture = imageCapture ?: return
        if (capturePending || frozenFrame || !ApiSettingsStore.freezeSelection(this)) return
        editingAddress = false
        invalidateCandidateWork()
        conversionRequests.invalidate()
        capturePending = true
        scannerPaused = true
        binding.freezeButton.isEnabled = false
        val generation = synchronized(candidateInputLock) { candidateGeneration }
        val frame = binding.scanFrame
        val border = resources.getDimension(R.dimen.scan_frame_stroke)
        val initial = FloatBox((frame.left + border) / binding.cameraPane.width,
            (frame.top + border) / binding.cameraPane.height,
            (frame.right - border) / binding.cameraPane.width, (frame.bottom - border) / binding.cameraPane.height)
        capture.targetRotation = binding.previewView.display.rotation
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var photo: Bitmap? = null
                var decoded: Bitmap? = null
                var cropped: Bitmap? = null
                try {
                    decoded = image.toBitmap()
                    val crop = image.cropRect
                    cropped = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width(), crop.height())
                    photo = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
                        Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
                    val finalPhoto = photo
                    runOnUiThread {
                        if (isDestroyed || synchronized(candidateInputLock) { generation != candidateGeneration }) {
                            finalPhoto.recycle(); return@runOnUiThread
                        }
                        capturePending = false; frozenFrame = true
                        binding.frozenSelection.setPhoto(finalPhoto, initial)
                        updateFrozenControls()
                        binding.frozenSelection.doOnLayout { if (frozenFrame) recognizeFrozenSelection() }
                    }
                } catch (_: Exception) {
                    photo?.recycle()
                    runOnUiThread { captureFailure(generation) }
                } finally {
                    if (cropped !== photo) cropped?.recycle()
                    if (decoded !== cropped && decoded !== photo) decoded?.recycle()
                    image.close()
                }
            }
            override fun onError(exception: ImageCaptureException) { runOnUiThread { captureFailure(generation) } }
        })
    }

    private fun captureFailure(generation: Long) {
        if (isDestroyed || synchronized(candidateInputLock) { generation != candidateGeneration }) return
        capturePending = false
        resumeScanning(clearResult = true)
        updateFrozenControls()
        Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_LONG).show()
    }

    private fun updateFrozenControls() {
        binding.freezeButton.isVisible = ApiSettingsStore.freezeSelection(this) && !frozenFrame
        binding.freezeButton.isEnabled = !capturePending
        binding.frozenSelection.isVisible = frozenFrame
        binding.frozenActions.isVisible = frozenFrame
        binding.scanFrame.isVisible = !frozenFrame
        updateScanButton()
    }

    private fun recognizeFrozenSelection() {
        if (!frozenFrame) return
        resumeScanning(clearResult = true)
        scannerPaused = true
        val generation = synchronized(candidateInputLock) { candidateGeneration }
        fun startWhenReady() {
            if (isDestroyed || !frozenFrame || synchronized(candidateInputLock) { generation != candidateGeneration }) return
            if (!processingFrame.compareAndSet(false, true)) {
                binding.root.postDelayed({ startWhenReady() }, 100L)
                return
            }
            val bitmap = binding.frozenSelection.cropSelection()
            if (bitmap == null) { processingFrame.set(false); return }
            diagnosticArea = "freeze 원본 ROI ${binding.frozenSelection.selectionBounds()}, ${bitmap.width}×${bitmap.height}"
            processCroppedBitmap(bitmap, 0, bitmap.width, bitmap.height, generation,
                SystemClock.elapsedRealtime(), retry = true, frozen = true)
        }
        startWhenReady()
    }

    private fun resumeCameraFrame() {
        capturePending = false; frozenFrame = false
        binding.frozenSelection.clearPhoto()
        resumeScanning(clearResult = true)
        updateFrozenControls()
    }

    private fun updateDiagnostics() {
        binding.developerText.isVisible = developerMode
        binding.developerText.text = if (!developerMode) "" else
            "OCR 원문: $lastOcrSourceText\n실제 처리 영역: $diagnosticArea\n추출 주소: $diagnosticExtraction\n검색 결과: $diagnosticSearch\n단계: $diagnosticStage"
        binding.developerCrop.isVisible = developerMode && diagnosticBitmap != null
        if (!developerMode) {
            clearDiagnosticImage()
        }
    }

    private fun clearDiagnosticImage() {
        binding.developerCrop.setImageDrawable(null)
        binding.developerCrop.isVisible = false
        diagnosticBitmap?.recycle(); diagnosticBitmap = null
    }

    private fun requestCameraIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        showPermissionPanel(!granted)
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionPanel(show: Boolean) { binding.permissionPanel.isVisible = show }

    private fun startCamera() {
        binding.previewView.post {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = binding.previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }
                    val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(2560, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                        .build()
                    imageCapture = capture
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis).addUseCase(capture)
                        .setViewPort(checkNotNull(binding.previewView.viewPort)).build()
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                    binding.flashButton.isVisible = camera?.cameraInfo?.hasFlashUnit() == true
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.camera_start_failed, Toast.LENGTH_LONG).show()
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (scannerPaused || frozenFrame || capturePending || editingAddress || now - lastAnalysisAt < ANALYSIS_INTERVAL_MS ||
            !processingFrame.compareAndSet(false, true)
        ) {
            imageProxy.close()
            return
        }
        lastAnalysisAt = now
        val generation = synchronized(candidateInputLock) { candidateGeneration }
        // Preview transforms and view bounds must be read on the UI thread.
        runOnUiThread {
            val window = if (isDestroyed || scannerPaused || frozenFrame || capturePending || editingAddress ||
                synchronized(candidateInputLock) { generation != candidateGeneration }) null
                else runCatching { snapshotScanWindow(imageProxy) }.getOrNull()
            if (window == null) {
                imageProxy.close()
                processingFrame.set(false)
                return@runOnUiThread
            }
            try {
                cameraExecutor.execute { recognizeScanWindow(imageProxy, window, now, generation) }
            } catch (_: RejectedExecutionException) {
                imageProxy.close()
                processingFrame.set(false)
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun snapshotScanWindow(imageProxy: ImageProxy): ScanWindow? {
        if (binding.previewView.outputTransform == null) return null
        if (binding.scanFrame.width <= 0 || binding.scanFrame.height <= 0) return null
        val previewLocation = IntArray(2)
        val frameLocation = IntArray(2)
        binding.previewView.getLocationInWindow(previewLocation)
        binding.scanFrame.getLocationInWindow(frameLocation)
        val scanRect = RectF(
            (frameLocation[0] - previewLocation[0]).toFloat(),
            (frameLocation[1] - previewLocation[1]).toFloat(),
            (frameLocation[0] - previewLocation[0] + binding.scanFrame.width).toFloat(),
            (frameLocation[1] - previewLocation[1] + binding.scanFrame.height).toFloat()
        )
        val border = resources.getDimension(R.dimen.scan_frame_stroke)
        scanRect.inset(border, border)
        if (scanRect.isEmpty) return null
        val previewWidth = scanRect.width()
        val previewHeight = scanRect.height()
        val visible = imageProxy.cropRect
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bounds = FrameGeometry.viewToBuffer(FloatBox(scanRect.left, scanRect.top, scanRect.right, scanRect.bottom),
            binding.previewView.width.toFloat(), binding.previewView.height.toFloat(),
            ScanPixelBounds(maxOf(0, visible.left), maxOf(0, visible.top),
                minOf(imageProxy.width, visible.right), minOf(imageProxy.height, visible.bottom)), rotation) ?: return null
        val radius = (resources.getDimension(R.dimen.scan_frame_corner_radius) - border).coerceAtLeast(0f)
        val quarterTurn = rotation == 90 || rotation == 270
        diagnosticArea = "live ${imageProxy.width}×${imageProxy.height}, 회전 $rotation°, ROI $bounds"
        return ScanWindow(bounds, rotation,
            radius * bounds.width / if (quarterTurn) previewHeight else previewWidth,
            radius * bounds.height / if (quarterTurn) previewWidth else previewHeight)
    }

    private fun recognizeScanWindow(imageProxy: ImageProxy, window: ScanWindow, now: Long, generation: Long) {
        var bitmap: Bitmap? = null
        try {
            if (isDestroyed || scannerPaused || frozenFrame || capturePending || editingAddress ||
                synchronized(candidateInputLock) { generation != candidateGeneration }) {
                processingFrame.set(false)
                return
            }
            val plane = imageProxy.planes.first()
            val bounds = window.bounds
            val pixels = ScanFramePixels.copyLuminance(plane.buffer, plane.rowStride, plane.pixelStride,
                bounds, window.radiusX, window.radiusY)
            val cropped = Bitmap.createBitmap(pixels, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
            bitmap = cropped
            val quarterTurn = window.rotation == 90 || window.rotation == 270
            val width = if (quarterTurn) bounds.height else bounds.width
            val height = if (quarterTurn) bounds.width else bounds.height
            val retry = now - lastRetryAt >= 2_000L
            if (retry) lastRetryAt = now
            processCroppedBitmap(cropped, window.rotation, width, height, generation, now, retry, false)
        } catch (_: Exception) {
            // Never fall back to whole-frame OCR when the crop is temporarily unavailable.
            bitmap?.recycle()
            processingFrame.set(false)
        } finally {
            imageProxy.close()
        }
    }

    private fun extractBlocksFromScanWindow(text: Text, width: Int, height: Int): List<FramedBlock> {
        val safeBounds = Rect(1, 1, width - 1, height - 1)
        val lines = text.textBlocks.flatMap { it.lines }.flatMap { line ->
            // Split distant words within a single ML Kit line before spatial grouping.
            val groups = mutableListOf<MutableList<Text.Element>>()
            line.elements.forEach { element ->
                val box = element.boundingBox ?: return@forEach
                if (!safeBounds.contains(box)) return@forEach
                val previous = groups.lastOrNull()?.lastOrNull()?.boundingBox
                if (previous == null || box.left - previous.right > maxOf(previous.height(), box.height()) * 1.8f)
                    groups += mutableListOf(element) else groups.last() += element
            }
            groups.map { group ->
                val bounds = Rect(group.first().boundingBox!!)
                group.drop(1).forEach { bounds.union(it.boundingBox!!) }
                OcrLine(group.joinToString(" ") { it.text }, FloatBox(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()))
            }
        }
        return OcrLineAssembler.assemble(lines).map { block ->
            FramedBlock(block.text, (block.box.left + block.box.right) / 2 / width,
                (block.box.top + block.box.bottom) / 2 / height)
        }
    }

    private fun processCroppedBitmap(bitmap: Bitmap, rotation: Int, width: Int, height: Int,
                                     generation: Long, now: Long, retry: Boolean, frozen: Boolean) {
        if (developerMode) {
            val factor = minOf(1f, 360f / maxOf(bitmap.width, bitmap.height))
            val thumbnail = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postScale(factor, factor); postRotate(rotation.toFloat()) }, true)
                .let { if (it === bitmap) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            runOnUiThread {
                if (isDestroyed || !developerMode || synchronized(candidateInputLock) { generation != candidateGeneration }) thumbnail.recycle()
                else {
                    binding.developerCrop.setImageBitmap(thumbnail)
                    diagnosticBitmap?.recycle(); diagnosticBitmap = thumbnail
                    binding.developerCrop.isVisible = true
                }
            }
        }
        ocrProcessor.recognize(bitmap, rotation, retry, current = {
            !isDestroyed && !editingAddress && frozenFrame == frozen && !capturePending &&
                (frozen || !scannerPaused) && synchronized(candidateInputLock) { generation == candidateGeneration }
        }, result = { text, retried -> runOnUiThread {
            if (isDestroyed || editingAddress || synchronized(candidateInputLock) { generation != candidateGeneration }) return@runOnUiThread
            val blocks = text?.let { extractBlocksFromScanWindow(it, width, height) }.orEmpty()
            lastOcrSourceText = text?.text.orEmpty()
            lastReconstructedText = AddressTextParser.restoreStructuredText(lastOcrSourceText)
            diagnosticStage = if (lastOcrSourceText.isBlank()) "OCR" else "주소 추출"
            if (retried) diagnosticArea += " · 대비 보정 1회"
            updateDiagnostics()
            scheduleCandidateCalculation(blocks, now)
        } }, finished = { processingFrame.set(false) })
    }

    private fun scheduleCandidateCalculation(blocks: List<FramedBlock>, now: Long) {
        val shouldStartWorker = synchronized(candidateInputLock) {
            pendingCandidateInput = CandidateInput(
                blocks = blocks,
                now = now,
                region = currentRegion,
                generation = candidateGeneration,
                sequence = ++frameSequence
            )
            if (candidateWorkerRunning) false else {
                candidateWorkerRunning = true
                true
            }
        }
        if (shouldStartWorker) dictionaryExecutor.execute(::drainCandidateInputs)
    }

    private fun drainCandidateInputs() {
        val input = synchronized(candidateInputLock) {
            pendingCandidateInput.also { pendingCandidateInput = null } ?: run {
                candidateWorkerRunning = false
                return
            }
        }
        val batch = candidatesFromRecentFrames(input)
        val isLatest = synchronized(candidateInputLock) { input.generation == candidateGeneration && input.sequence == frameSequence }
        if (isLatest) {
            onCandidateFrame(batch.candidates, input.now, batch.dictionaryBacked, input.generation, input.sequence)
        }

        val hasPendingInput = synchronized(candidateInputLock) {
            if (pendingCandidateInput == null) {
                candidateWorkerRunning = false
                false
            } else true
        }
        // Yield to a queued dictionary reload between OCR frames instead of monopolizing the executor.
        if (hasPendingInput && !dictionaryExecutor.isShutdown) {
            dictionaryExecutor.execute(::drainCandidateInputs)
        }
    }

    private fun candidatesFromRecentFrames(input: CandidateInput): CandidateBatch {
        val blocks = input.blocks
        if (blocks.isEmpty()) return CandidateBatch(emptyList(), dictionaryReady)
        val engine = candidateEngine
        val dictionaryBacked = engine != null && dictionaryReady
        // Restore only spatially joined lines from THIS frame. Never combine past addresses.
        val grouped = blocks.map { listOf(it.text) }
        val candidates = grouped.flatMap { samples ->
            engine?.candidates(samples, input.region)
                ?: AddressTextParser.extractCandidatesFromOcrSamples(samples, includePartial = true)
        }.distinctBy(CandidateTracker::identity).take(MAX_ADDRESS_CANDIDATES)
        return CandidateBatch(candidates, dictionaryBacked)
    }

    private fun onCandidateFrame(
        incoming: List<AddressCandidate>,
        now: Long,
        dictionaryBacked: Boolean,
        generation: Long,
        sequence: Long
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed || (scannerPaused && !frozenFrame) || candidateTouchActive ||
                editingAddress || binding.addressInput.hasFocus() ||
                (!frozenFrame && SystemClock.elapsedRealtime() - now > 1_500L) ||
                synchronized(candidateInputLock) { generation != candidateGeneration || sequence != frameSequence }) {
                return@runOnUiThread
            }
            val selected = selectedCandidate
            if (!continuousScan && selected != null) return@runOnUiThread
            if (continuousScan && selected != null && incoming.isNotEmpty()) {
                if (incoming.any { CandidateTracker.isSameAddressFamily(it, selected) &&
                        (it.details.isBlank() || AddressTextParser.normalizeKey(it.details) == AddressTextParser.normalizeKey(selected.details)) }) {
                    nextSignature = null
                    nextCount = 0
                    return@runOnUiThread
                }
                val incomingSignature = CandidateTracker.identity(incoming.first())
                if (incomingSignature == nextSignature) nextCount++ else {
                    nextSignature = incomingSignature
                    nextCount = 1
                }
                if (nextCount < REQUIRED_NEW_ADDRESS_FRAMES) return@runOnUiThread
                selectedCandidate = null
                candidateTracker.unfreeze()
                conversionRequests.invalidate()
                autoConversion.reset()
                nextSignature = null
                nextCount = 0
            }

            val candidates = candidateTracker.update(incoming, now)
            renderCandidates(candidates)
            diagnosticExtraction = incoming.joinToString("\n") { it.text + " " + it.details }
            diagnosticStage = if (incoming.isEmpty()) { if (lastOcrSourceText.isBlank()) "OCR" else "주소 추출" } else "검색 전"
            updateDiagnostics()
            if (frozenFrame && incoming.isEmpty()) binding.statusText.setText(R.string.selection_not_found)
            if (selectedCandidate != null) return@runOnUiThread
            // List visibility and weaker alternatives must not hold up a clear address.
            (if (frozenFrame) AutoConversionPolicy(1) else autoConversion).update(incoming, dictionaryBacked)?.let(::acceptCandidate)
        }
    }

    private fun renderCandidates(candidates: List<AddressCandidate>) {
        if (candidateTouchActive) return
        displayedCandidates = candidates.take(3)
        // Ambiguous addresses must remain selectable even if the optional list was hidden.
        binding.candidatePanel.isVisible = frozenFrame || showCandidateList || !internetAvailable || awaitingNetwork ||
            candidates.any { it.manualOnly } ||
            candidates.size > 1 ||
            candidates.any { it.confidence < 88 }
        val selectedKey = selectedCandidate?.let(CandidateTracker::identity)
        val renderKey = displayedCandidates.joinToString("|") { "${it.text}:${it.completeness}:${it.details}:${it.manualOnly}:${it.alternativeTarget}" } + selectedKey
        if (renderKey == renderedCandidateKey) return
        renderedCandidateKey = renderKey
        binding.candidateContainer.removeAllViews()
        displayedCandidates.forEach { candidate ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                isAllCaps = false
                val selected = CandidateTracker.identity(candidate) == selectedKey
                text = when {
                    candidate.manualOnly -> getString(R.string.similar_address_manual, candidate.text) + "\n→ ${candidate.alternativeTarget}"
                    selected -> getString(R.string.candidate_selected, candidate.text)
                    candidate.completeness == CandidateCompleteness.PARTIAL ->
                        getString(R.string.candidate_partial, candidate.text)
                    else -> candidate.text + if (candidate.details.isNotBlank()) "\n${candidate.details}" else ""
                }
                textSize = 13f
                maxLines = if (candidate.manualOnly) 4 else 3
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = (48 * resources.displayMetrics.density).toInt()
                minimumHeight = minHeight
                insetTop = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
                setTextColor(ContextCompat.getColor(this@MainActivity, if (selected) R.color.blue else R.color.ink))
                strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, if (selected) R.color.blue else R.color.line)
                )
                setOnClickListener {
                    hideKeyboard()
                    candidateTouchActive = false
                    acceptCandidate(candidate)
                }
            }
            binding.candidateContainer.addView(button)
        }
        binding.candidateEmptyText.isVisible = candidates.isEmpty()
    }

    private fun acceptCandidate(candidate: AddressCandidate) {
        val key = CandidateTracker.identity(candidate)
        val explicitlyChosenResult = if (candidate.manualOnly) apiAlternatives[key] else null
        if (lastSubmittedKey == key && !awaitingNetwork && !editingAddress) return
        editingAddress = false
        invalidateCandidateWork()
        autoConversion.reset()
        if (candidate.completeness != CandidateCompleteness.COMPLETE) {
            // Keep reading the number after a partial candidate is selected.
            conversionRequests.invalidate()
            awaitingNetwork = false
            binding.convertButton.isEnabled = true
            selectedCandidate = null
            scannerPaused = frozenFrame
            candidateTracker.unfreeze(keepVisible = true)
            binding.addressInput.setText(candidate.text)
            binding.statusText.setText(R.string.partial_candidate_status)
            binding.roadAddressText.setText(R.string.waiting_for_number)
            currentMapAddress = null
            binding.mapButton.isEnabled = false
            updateScanButton()
            return
        }
        val alternatives = displayedCandidates.toList()
        candidateTracker.freeze(candidate, alternatives)
        selectedCandidate = candidate
        if (!continuousScan || frozenFrame) scannerPaused = true
        binding.addressInput.setText(candidate.text + if (candidate.details.isNotBlank()) " ${candidate.details}" else "")
        binding.detailText.isVisible = candidate.details.isNotBlank()
        binding.detailText.text = getString(R.string.recognized_details, candidate.details)
        renderCandidates((listOf(candidate) + alternatives).distinctBy(CandidateTracker::identity))
        updateScanButton()
        lastSubmittedKey = key
        if (explicitlyChosenResult != null) {
            conversionRequests.invalidate()
            binding.convertButton.isEnabled = true
            showSuccess(explicitlyChosenResult)
        } else convertAddress(candidate.text)
    }

    private fun submitManualAddress() {
        val raw = binding.addressInput.text?.toString().orEmpty()
        val candidate = AddressTextParser.extractCandidate(raw)
        if (candidate == null) {
            binding.addressInputLayout.error = getString(R.string.enter_complete_address)
            return
        }
        val parts = AddressTextParser.parseParts(candidate.text)
        val text = if (parts?.prefix?.isEmpty() == true && !currentRegion.isEmpty) {
            "${currentRegion.displayName()} ${candidate.text}"
        } else candidate.text
        acceptCandidate(candidate.copy(text = text, details = AddressDetails.extract(raw, candidate.text)))
    }

    private fun convertAddress(rawAddress: String) {
        // A tapped candidate is already chosen: never silently re-correct it to another address.
        val parsed = AddressTextParser.extract(rawAddress)
            ?: rawAddress.trim().takeIf(String::isNotEmpty)
        if (parsed == null || AddressTextParser.parseParts(parsed)?.number == null) {
            binding.addressInputLayout.error = getString(R.string.enter_complete_address)
            return
        }
        val requestId = conversionRequests.begin()
        awaitingNetwork = false
        currentMapAddress = null
        binding.mapButton.isEnabled = false
        binding.addressInputLayout.error = null
        binding.convertButton.isEnabled = false
        binding.convertedAddressLabel.setText(R.string.converted_label)
        binding.roadAddressText.setTextColor(ContextCompat.getColor(this, R.color.ink))
        binding.roadAddressText.setText(R.string.converting_address)
        binding.statusText.setText(R.string.dictionary_candidate_waiting_api)
        diagnosticStage = "검색 중"; diagnosticSearch = ""; updateDiagnostics()

        converter.convert(parsed) { outcome ->
            runOnUiThread {
                if (isFinishing || isDestroyed || !conversionRequests.finish(requestId)) return@runOnUiThread
                binding.convertButton.isEnabled = true
                when (outcome) {
                    is ConversionOutcome.Success -> showSuccess(outcome.result)
                    ConversionOutcome.ApiKeyMissing -> {
                        showError(getString(R.string.api_required))
                        openSettings()
                    }
                    ConversionOutcome.NotFound -> showError(getString(R.string.address_not_verified))
                    is ConversionOutcome.NoExactMatch -> {
                        showError(getString(R.string.address_not_verified))
                        apiAlternatives.clear()
                        val similar = outcome.suggestions.map { result -> AddressCandidate(
                            result.recognizedAddress, result.recognizedKind, confidence = 0, manualOnly = true,
                            alternativeTarget = result.convertedAddress).also { apiAlternatives[CandidateTracker.identity(it)] = result } }
                        candidateTracker.freeze(selectedCandidate ?: AddressCandidate(parsed, AddressTextParser.classify(parsed)), similar)
                        renderCandidates(similar)
                    }
                    ConversionOutcome.Offline -> showLocalResult(offline = true)
                    is ConversionOutcome.NetworkError -> showLocalResult(
                        offline = !NetworkAvailability.isOnline(this),
                        message = getString(R.string.address_network_error, outcome.message)
                    )
                }
            }
        }
    }

    private fun showSuccess(result: AddressResult) {
        diagnosticStage = "완료"; diagnosticSearch = result.convertedAddress; updateDiagnostics()
        awaitingNetwork = false
        currentMapAddress = result.convertedAddress
        binding.mapButton.isEnabled = true
        binding.roadAddressText.text = result.convertedAddress
        binding.roadAddressText.setTextColor(ContextCompat.getColor(this, R.color.success))
        binding.convertedAddressLabel.text = when (result.recognizedKind) {
            AddressKind.PARCEL -> getString(R.string.converted_road_label)
            AddressKind.ROAD -> getString(R.string.converted_parcel_label)
            AddressKind.UNKNOWN -> getString(R.string.converted_label)
        }
        binding.statusText.text = when (result.source) {
            AddressResult.Source.VWORLD -> getString(R.string.verified_by_vworld)
            AddressResult.Source.NAVER -> getString(R.string.verified_by_naver)
            AddressResult.Source.KAKAO -> getString(R.string.verified_by_kakao)
        }
    }

    private fun showError(message: String) {
        diagnosticStage = "검색"; diagnosticSearch = message; updateDiagnostics()
        awaitingNetwork = false
        currentMapAddress = null
        binding.mapButton.isEnabled = false
        binding.roadAddressText.text = if (message == getString(R.string.address_not_verified)) message else getString(R.string.conversion_failed)
        binding.roadAddressText.setTextColor(ContextCompat.getColor(this, R.color.error))
        binding.statusText.text = message
    }

    /** OCR/dictionary selection is valid local work, not proof of a real building address. */
    private fun showLocalResult(offline: Boolean, message: String = "") {
        diagnosticStage = "검색"; diagnosticSearch = if (offline) "오프라인" else message; updateDiagnostics()
        awaitingNetwork = true
        currentMapAddress = null
        binding.mapButton.isEnabled = false
        binding.convertButton.isEnabled = true
        binding.convertedAddressLabel.setText(R.string.local_address_label)
        binding.roadAddressText.setText(R.string.local_address_ready)
        binding.roadAddressText.setTextColor(ContextCompat.getColor(this, R.color.ink))
        binding.statusText.text = if (offline) getString(R.string.offline_conversion_status)
            else getString(R.string.local_address_network_failure, message)
        // Keep all choices and the selected input intact, even with the optional list hidden.
        renderCandidates(displayedCandidates)
    }

    override fun onStart() {
        super.onStart()
        networkAvailability.start(::onConnectivityChanged)
    }

    override fun onStop() {
        invalidateCandidateWork()
        if (capturePending) { capturePending = false; scannerPaused = false }
        networkAvailability.stop()
        super.onStop()
    }

    private fun onConnectivityChanged(online: Boolean) {
        if (isFinishing || isDestroyed) return
        internetAvailable = online
        if (!online && conversionRequests.pending) {
            // A late API reply must not replace the retained local selection after disconnection.
            conversionRequests.invalidate()
            showLocalResult(offline = true)
        } else if (awaitingNetwork) {
            binding.statusText.setText(if (online) R.string.connection_restored_retry
                else R.string.offline_conversion_status)
        } else if (!online && binding.statusText.text.isBlank()) {
            binding.statusText.setText(R.string.offline_scanning_status)
        } else if (online && binding.statusText.text == getString(R.string.offline_scanning_status)) {
            binding.statusText.text = ""
        }
        renderCandidates(displayedCandidates)
        if (online) initializeDictionaryUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (::converter.isInitialized) {
            val credentials = ApiSettingsStore.load(this)
            converter.updateApiKeys(
                credentials.vworldApiKey, credentials.naverClientId,
                credentials.naverClientSecret, credentials.kakaoRestApiKey
            )
        }
        val savedRegion = ApiSettingsStore.loadRegion(this)
        if (savedRegion != currentRegion) {
            currentRegion = savedRegion
            invalidateCandidateWork()
            autoConversion.reset()
            if (selectedCandidate == null) {
                candidateTracker.clear()
                renderCandidates(emptyList())
            }
        }
        applyScannerPreferences()
        if (::dictionaryExecutor.isInitialized) loadDictionary()
        if (internetAvailable && !automaticUpdateCheckStarted && UpdateChecker.shouldAutoCheck(this)) {
            automaticUpdateCheckStarted = true
            binding.root.postDelayed(::checkForUpdatesAutomatically, 1_500L)
        }
    }

    private fun applyScannerPreferences() {
        developerMode = ApiSettingsStore.developerMode(this)
        updateDiagnostics()
        if (frozenFrame && !ApiSettingsStore.freezeSelection(this)) resumeCameraFrame()
        updateFrozenControls()
        showCandidateList = ApiSettingsStore.showCandidateList(this)
        val newContinuous = ApiSettingsStore.continuousScan(this)
        if (newContinuous != continuousScan) {
            continuousScan = newContinuous
            scannerPaused = !continuousScan && selectedCandidate != null
            invalidateCandidateWork()
        }
        binding.regionButton.text = currentRegion.displayName()
        renderCandidates(displayedCandidates)
        updateScanButton()
    }

    private fun togglePause() {
        scannerPaused = !scannerPaused
        invalidateCandidateWork()
        autoConversion.reset()
        updateScanButton()
    }

    private fun updateScanButton() {
        binding.resetRecognitionButton.isVisible = continuousScan && !frozenFrame
        binding.scanAgainButton.setText(when {
            frozenFrame -> R.string.scan_again
            !continuousScan -> R.string.scan_again
            scannerPaused -> R.string.resume_scan
            else -> R.string.pause_scan
        })
    }

    private fun invalidateCandidateWork() {
        synchronized(candidateInputLock) {
            candidateGeneration++
            pendingCandidateInput = null
        }
    }

    private fun resumeScanning(clearResult: Boolean) {
        editingAddress = false
        lastSubmittedKey = null
        apiAlternatives.clear()
        scannerPaused = false
        conversionRequests.invalidate()
        awaitingNetwork = false
        selectedCandidate = null
        candidateTracker.clear()
        invalidateCandidateWork()
        autoConversion.reset()
        nextSignature = null
        nextCount = 0
        renderCandidates(emptyList())
        if (clearResult) {
            clearDiagnosticImage()
            lastOcrSourceText = ""; diagnosticExtraction = ""; diagnosticSearch = ""; diagnosticStage = ""
            binding.detailText.text = ""; binding.detailText.isVisible = false
            updateDiagnostics()
            currentMapAddress = null
            binding.addressInput.text?.clear()
            binding.roadAddressText.setTextColor(ContextCompat.getColor(this, R.color.ink))
            binding.roadAddressText.setText(R.string.waiting_road)
            binding.convertedAddressLabel.setText(R.string.converted_label)
            binding.statusText.text = if (internetAvailable) "" else getString(R.string.offline_scanning_status)
            binding.mapButton.isEnabled = false
        }
        binding.convertButton.isEnabled = true
        updateScanButton()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun openRegionSettings() = startActivity(
        Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_REGION, true)
    )

    private fun checkForUpdatesAutomatically() {
        if (isFinishing || isDestroyed || !NetworkAvailability.isOnline(this)) return
        UpdateChecker.check(BuildConfig.VERSION_NAME) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is UpdateCheckResult.Available -> {
                        UpdateChecker.recordSuccessfulCheck(this)
                        showUpdateDialog(result.release)
                    }
                    UpdateCheckResult.UpToDate, UpdateCheckResult.NoRelease ->
                        UpdateChecker.recordSuccessfulCheck(this)
                    is UpdateCheckResult.Error -> Unit
                }
            }
        }
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available)
            .setMessage(getString(R.string.update_available_message, release.version))
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(if (release.hasApkAsset) R.string.download_apk else R.string.open_github) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
            }.show()
    }

    private fun openCurrentAddressInMap(forceChooser: Boolean) {
        val address = currentMapAddress ?: return
        val preferences = ApiSettingsStore.preferences(this)
        val saved = preferences.getString(PREFERRED_MAP_PROVIDER, null)
            ?.let { value -> MapProvider.values().firstOrNull { it.name == value } }
        if (forceChooser || saved == null) {
            val providers = MapProvider.values()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_map_app)
                .setItems(providers.map { getString(it.label) }.toTypedArray()) { _, index ->
                    val provider = providers[index]
                    preferences.edit().putString(PREFERRED_MAP_PROVIDER, provider.name).apply()
                    Toast.makeText(this, R.string.map_provider_saved, Toast.LENGTH_SHORT).show()
                    launchMap(provider, address)
                }.setNegativeButton(R.string.cancel, null).show()
        } else launchMap(saved, address)
    }

    private fun launchMap(provider: MapProvider, address: String) {
        val appIntent = when (provider) {
            MapProvider.NAVER -> Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("nmap").authority("search")
                .appendQueryParameter("query", address).appendQueryParameter("appname", packageName).build())
                .setPackage("com.nhn.android.nmap")
            MapProvider.KAKAO -> Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("kakaomap").authority("search")
                .appendQueryParameter("q", address).build()).setPackage("net.daum.android.map")
        }
        try {
            startActivity(appIntent)
        } catch (_: ActivityNotFoundException) {
            val url = when (provider) {
                MapProvider.NAVER -> "https://map.naver.com/p/search/${Uri.encode(address)}"
                MapProvider.KAKAO -> "https://map.kakao.com/link/search/${Uri.encode(address)}"
            }
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.map_app_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        invalidateCandidateWork()
        binding.frozenSelection.clearPhoto()
        binding.developerCrop.setImageDrawable(null)
        diagnosticBitmap?.recycle(); diagnosticBitmap = null
        recognizer.close()
        converter.close()
        cameraExecutor.shutdown()
        dictionaryExecutor.shutdown()
        super.onDestroy()
    }

    private data class FramedBlock(val text: String, val centerX: Float, val centerY: Float)

    private data class ScanWindow(
        val bounds: ScanPixelBounds, val rotation: Int, val radiusX: Float, val radiusY: Float
    )

    private data class CandidateInput(
        val blocks: List<FramedBlock>,
        val now: Long,
        val region: RegionSelection,
        val generation: Long,
        val sequence: Long
    )

    private data class CandidateBatch(
        val candidates: List<AddressCandidate>,
        val dictionaryBacked: Boolean
    )

    private enum class MapProvider(val label: Int) {
        NAVER(R.string.naver_map_app), KAKAO(R.string.kakao_map_app)
    }

    companion object {
        private const val ANALYSIS_INTERVAL_MS = 300L
        private const val REQUIRED_NEW_ADDRESS_FRAMES = 2
        private const val MAX_ADDRESS_CANDIDATES = 5
        private const val PREFERRED_MAP_PROVIDER = "preferred_map_provider"
    }
}
