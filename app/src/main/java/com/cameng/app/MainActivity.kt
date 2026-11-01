package com.cameng.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.cameng.app.databinding.ActivityMainBinding
import com.cameng.app.drive.DriveAuth
import com.cameng.app.drive.DriveClient
import com.cameng.app.drive.UploadScheduler
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { Prefs(this) }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var estimator: ScaleEstimator? = null

    private var torchOn = false
    private var zoomRatio = 1f
    private var deviceOrientation = 0
    private var updatingZoomBar = false
    private lateinit var orientationListener: OrientationEventListener

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.CAMERA] == true) startCamera()
            else Toast.makeText(this, "Sem permissão de câmera o app não funciona.", Toast.LENGTH_LONG).show()
        }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            try {
                val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
                onAuthorized(r)
            } catch (e: Exception) {
                showAuthError(e)
            }
        }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupComment()
        setupRuler()
        setupZoom()
        setupGestures()

        b.shutter.setOnClickListener { takePhoto() }
        b.torch.setOnClickListener { setTorch(!torchOn) }
        b.gallery.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        b.slowmo.setOnClickListener {
            // Libera a câmera para a tela de câmera lenta (que usa a Camera2 diretamente).
            cameraProvider?.unbindAll()
            camera = null
            startActivity(Intent(this, SlowMotionActivity::class.java))
        }
        b.driveStatus.setOnClickListener { onDriveClicked() }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientation = ((orientation + 45) / 90 * 90) % 360
            }
        }

        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(UploadScheduler.NOW)
            .observe(this) { refreshDriveStatus() }

        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) startCamera()
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        refreshDriveStatus()
        b.root.post(ticker)
        UploadScheduler.enqueueNow(this)
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
        b.root.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- câmera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val previewBuilder = Preview.Builder().setResolutionSelector(previewSelector)
            // Lê a distância de foco de cada quadro (usada na régua real automática).
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                    ) {
                        estimator?.focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    }
                }
            )
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(b.preview.surfaceProvider)

            // ~12 MP em 4:3: boa qualidade sem estourar memória ao desenhar na foto.
            val captureSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(captureSelector)
                .build()
            imageCapture = capture

            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                camera = cam
                setupEstimator(cam)
                cam.cameraInfo.zoomState.observe(this) { z -> onZoomChanged(z.zoomRatio, z.linearZoom) }
                if (torchOn) cam.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao abrir a câmera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupEstimator(cam: Camera) {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val phys = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 0f
        val calib = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
            ?: android.hardware.camera2.CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
        val longSideMm = if (phys != null && pixels != null && active != null && pixels.width > 0) {
            phys.width * active.width() / pixels.width
        } else 0f
        estimator = ScaleEstimator(longSideMm, focal, calib)
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        b.torch.isSelected = on
        camera?.cameraControl?.enableTorch(on)
    }

    // ---------------------------------------------------------------- zoom e gestos

    private fun setupZoom() {
        b.zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && !updatingZoomBar) camera?.cameraControl?.setLinearZoom(p / 1000f)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        b.zoom1x.setOnClickListener { camera?.cameraControl?.setZoomRatio(1f) }
    }

    private fun onZoomChanged(ratio: Float, linear: Float) {
        // A escala calibrada continua válida se só o zoom mudou (mesma distância).
        b.ruler.calibratedMmPerPx?.let { cal ->
            if (zoomRatio > 0f && ratio > 0f) b.ruler.calibratedMmPerPx = cal * zoomRatio / ratio
        }
        zoomRatio = ratio
        b.zoomLabel.text = String.format(Locale.US, "%.1fx", ratio)
        updatingZoomBar = true
        b.zoomBar.progress = (linear * 1000).toInt()
        updatingZoomBar = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val z = cam.cameraInfo.zoomState.value ?: return true
                val target = (z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                cam.cameraControl.setZoomRatio(target)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        b.preview.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress) tap.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = b.preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        hideKeyboard()
    }

    // ---------------------------------------------------------------- comentário

    private fun setupComment() {
        b.comment.setText(prefs.comment)
        b.comment.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) { prefs.comment = s?.toString().orEmpty() }
        })
        b.clearComment.setOnClickListener { b.comment.setText("") }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.comment.windowToken, 0)
        b.comment.clearFocus()
    }

    // ---------------------------------------------------------------- régua

    private fun setupRuler() {
        b.ruler.divisions = prefs.virtualDivisions
        applyRulerMode(prefs.rulerMode)
        b.rulerMode.setOnClickListener {
            val modes = RulerView.Mode.values()
            val labels = arrayOf(
                "Desligada",
                "Virtual (divisões, sem medida)",
                "Real automática (pela distância do foco)",
                "Real calibrada (com objeto de referência)",
            )
            AlertDialog.Builder(this)
                .setTitle("Régua / escala")
                .setSingleChoiceItems(labels, modes.indexOf(b.ruler.mode)) { d, which ->
                    applyRulerMode(modes[which])
                    d.dismiss()
                }
                .show()
        }
        b.rulerAction.setOnClickListener {
            when (b.ruler.mode) {
                RulerView.Mode.REAL_CALIBRATED -> showCalibrationDialog()
                RulerView.Mode.VIRTUAL -> showDivisionsDialog()
                else -> {}
            }
        }
    }

    private fun applyRulerMode(mode: RulerView.Mode) {
        prefs.rulerMode = mode
        b.ruler.mode = mode
        b.rulerMode.isSelected = mode != RulerView.Mode.OFF
        b.rulerMode.text = "📏 Régua: " + when (mode) {
            RulerView.Mode.OFF -> "desligada"
            RulerView.Mode.VIRTUAL -> "virtual"
            RulerView.Mode.REAL_AUTO -> "real (automática)"
            RulerView.Mode.REAL_CALIBRATED -> "real (calibrada)"
        }
        b.rulerAction.visibility = when (mode) {
            RulerView.Mode.REAL_CALIBRATED, RulerView.Mode.VIRTUAL -> android.view.View.VISIBLE
            else -> android.view.View.GONE
        }
        b.rulerAction.text = if (mode == RulerView.Mode.VIRTUAL) "Divisões" else "Calibrar"
        updateScaleInfo()
    }

    private fun showCalibrationDialog() {
        val refs = arrayOf(
            "Cartão de crédito / RG novo (85,6 mm)",
            "Moeda de R$ 1 (27 mm)",
            "Folha A4 – lado menor (210 mm)",
            "Folha A4 – lado maior (297 mm)",
            "Outra medida…",
        )
        val values = floatArrayOf(85.6f, 27f, 210f, 297f)
        AlertDialog.Builder(this)
            .setTitle("Calibrar: coloque as pontas da régua nas bordas do objeto")
            .setItems(refs) { _, which ->
                if (which < values.size) calibrate(values[which]) else askCustomLength()
            }
            .show()
    }

    private fun askCustomLength() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "comprimento em cm (ex.: 15)"
        }
        AlertDialog.Builder(this)
            .setTitle("Medida real entre as pontas da régua")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val cm = input.text.toString().replace(',', '.').toFloatOrNull()
                if (cm != null && cm > 0f) calibrate(cm * 10f)
                else Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun calibrate(mm: Float) {
        val px = b.ruler.lengthPx()
        if (px < 20f) {
            Toast.makeText(this, "Estique a régua sobre o objeto antes de calibrar.", Toast.LENGTH_LONG).show()
            return
        }
        b.ruler.calibratedMmPerPx = mm / px
        updateScaleInfo()
        Toast.makeText(this, "Calibrado! Mantenha a mesma distância do objeto.", Toast.LENGTH_LONG).show()
    }

    private fun showDivisionsDialog() {
        val options = intArrayOf(4, 5, 8, 10, 12, 16, 20, 50, 100)
        AlertDialog.Builder(this)
            .setTitle("Número de divisões da régua virtual")
            .setItems(options.map { it.toString() }.toTypedArray()) { _, which ->
                b.ruler.divisions = options[which]
                prefs.virtualDivisions = options[which]
                updateScaleInfo()
            }
            .show()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateScaleInfo()
            b.root.postDelayed(this, 300)
        }
    }

    private fun updateScaleInfo() {
        val r = b.ruler
        val info: String? = when (r.mode) {
            RulerView.Mode.OFF -> null
            RulerView.Mode.VIRTUAL -> "Régua virtual: ${r.divisions} divisões (não representa medida real)."
            RulerView.Mode.REAL_AUTO -> {
                val est = estimator
                val mm = est?.mmPerViewPx(zoomRatio, r.contentRect().height())
                r.autoMmPerPx = mm
                when {
                    est == null -> "Iniciando câmera…"
                    !est.supported -> "Este aparelho não informa a distância do foco. Use a régua calibrada."
                    mm == null -> "Toque no objeto para focar (muito longe ou foco indefinido)."
                    else -> "Distância ≈ ${RulerRenderer.formatLength(est.distanceMm ?: 0f)} · estimativa " +
                        "pelo foco (aprox.). Toque no objeto para focar."
                }
            }
            RulerView.Mode.REAL_CALIBRATED ->
                if (r.calibratedMmPerPx == null) "Coloque as pontas da régua num objeto de medida conhecida e toque em Calibrar."
                else "Calibrada ✔ — válida enquanto a distância até o objeto não mudar."
        }
        b.scaleInfo.text = info.orEmpty()
        b.scaleInfo.visibility = if (info == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ---------------------------------------------------------------- foto

    private fun takePhoto() {
        val capture = imageCapture ?: return
        hideKeyboard()
        updateScaleInfo()
        b.shutter.isEnabled = false
        b.flash.alpha = 0.8f
        b.flash.animate().alpha(0f).setDuration(250).start()

        val req = PhotoProcessor.Request(
            date = Date(),
            comment = b.comment.text.toString(),
            ruler = b.ruler.snapshot(),
            deviceOrientation = deviceOrientation,
        )
        capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = runCatching {
                    val rotation = image.imageInfo.rotationDegrees
                    val bmp = try { image.toBitmap() } finally { image.close() }
                    PhotoProcessor.process(applicationContext, bmp, rotation, req)
                }
                runOnUiThread {
                    b.shutter.isEnabled = true
                    result.onSuccess {
                        Toast.makeText(this@MainActivity, "Foto salva ✔", Toast.LENGTH_SHORT).show()
                        UploadScheduler.enqueueNow(this@MainActivity)
                        refreshDriveStatus()
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Erro ao salvar: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    b.shutter.isEnabled = true
                    Toast.makeText(this@MainActivity, "Erro na foto: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    // ---------------------------------------------------------------- Google Drive

    private fun refreshDriveStatus() {
        val pending = Storage.pendingFiles(this).size
        b.driveStatus.isSelected = false
        b.driveStatus.text = if (prefs.driveConnected) {
            val who = prefs.driveEmail?.substringBefore('@') ?: "Drive"
            if (pending == 0) "☁ $who\ntudo enviado" else "☁ $who\n$pending pendente(s)"
        } else {
            if (pending == 0) "☁ Conectar\nDrive" else "☁ Conectar Drive\n$pending pendente(s)"
        }
    }

    private fun onDriveClicked() {
        if (!prefs.driveConnected) {
            connectDrive()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Google Drive: ${prefs.driveEmail ?: "conectado"}")
            .setMessage("As fotos vão para a pasta CamEng no seu Drive, separadas por dia.")
            .setPositiveButton("Enviar agora") { _, _ -> UploadScheduler.enqueueNow(this) }
            .setNeutralButton("Trocar conta") { _, _ -> disconnectDrive(); connectDrive() }
            .setNegativeButton("Desconectar") { _, _ -> disconnectDrive() }
            .show()
    }

    private fun disconnectDrive() {
        prefs.driveConnected = false
        prefs.driveEmail = null
        prefs.driveRootFolderId = null
        refreshDriveStatus()
    }

    private fun connectDrive() {
        Identity.getAuthorizationClient(this)
            .authorize(DriveAuth.request())
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    onAuthorized(r)
                }
            }
            .addOnFailureListener { showAuthError(it) }
    }

    private fun onAuthorized(r: AuthorizationResult) {
        val token = r.accessToken
        if (token == null) {
            showAuthError(IllegalStateException("Autorização sem token"))
            return
        }
        lifecycleScope.launch {
            val email = withContext(Dispatchers.IO) { runCatching { DriveClient(token).aboutEmail() }.getOrNull() }
            prefs.driveConnected = true
            prefs.driveEmail = email
            refreshDriveStatus()
            UploadScheduler.enqueueNow(this@MainActivity)
            Toast.makeText(this@MainActivity, "Drive conectado: ${email ?: "ok"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAuthError(e: Exception) {
        AlertDialog.Builder(this)
            .setTitle("Não foi possível conectar ao Drive")
            .setMessage(
                "Confira se o app foi registrado no Google Cloud (passo a passo no README do projeto): " +
                    "API do Drive ativada, tela de consentimento publicada e ID de cliente Android com o " +
                    "pacote com.cameng.app e o SHA-1 correto.\n\nDetalhe: ${e.message}"
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
