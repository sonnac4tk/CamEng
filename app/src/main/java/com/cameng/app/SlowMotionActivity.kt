package com.cameng.app

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.cameng.app.databinding.ActivitySlowMotionBinding
import com.cameng.app.drive.UploadScheduler
import java.io.File
import java.util.Locale

/**
 * Gravação em câmera lenta usando Camera2.
 *
 * - Se o aparelho libera "constrained high speed video" para outros apps (120/240 fps),
 *   usa esse modo.
 * - Senão, grava na maior taxa comum disponível (ex.: 60 fps), se for maior que 30.
 *
 * O vídeo é gravado em alta taxa e salvo para tocar a 30 fps, ou seja, já fica lento
 * em qualquer player (120 fps = 4x mais lento, 240 fps = 8x).
 */
class SlowMotionActivity : ComponentActivity() {

    data class Option(val cameraId: String, val size: Size, val fps: Int, val highSpeed: Boolean) {
        val factor get() = fps / PLAYBACK_FPS
        val label get() = "${fps} fps · ${factor}x lento · ${size.height}p"
    }

    companion object {
        const val PLAYBACK_FPS = 30
    }

    private lateinit var b: ActivitySlowMotionBinding
    private val manager by lazy { getSystemService(CameraManager::class.java) }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recordingTmp: File? = null
    private var recordingTarget: File? = null
    private var recordStart = 0L

    /** Uma opção por taxa (fps) mostrada na tela. */
    private var options: List<Option> = emptyList()
    /** Para cada fps, os tamanhos que ainda podem ser tentados (se um falhar, tenta o próximo). */
    private val candidates = sortedMapOf<Int, MutableList<Option>>()
    private var selected: Option? = null
    /** Incrementado a cada abertura/configuração; callbacks antigos são ignorados. */
    @Volatile private var generation = 0
    /** true depois que o primeiro quadro chegou na configuração atual. */
    @Volatile private var framesArriving = false
    private var openAttempts = 0
    private var torchOn = false
    private var zoom = 1f
    private var maxZoom = 1f
    private var activeArray: Rect? = null
    private var sensorOrientation = 90
    private var deviceOrientation = 0
    private val recording get() = recorder != null

    private lateinit var orientationListener: OrientationEventListener

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySlowMotionBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SystemUi.fullscreen(this)
        SystemUi.padForInsets(b.root, b.topBar, b.bottomBar)

        b.back.setOnClickListener { finish() }
        b.record.setOnClickListener { if (recording) stopRecording() else startRecording() }
        b.torch.setOnClickListener {
            torchOn = !torchOn
            b.torch.isSelected = torchOn
            updateRepeating()
        }
        b.zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                zoom = 1f + (maxZoom - 1f) * p / 1000f
                b.zoomLabel.text = String.format(Locale.US, "%.1fx", zoom)
                updateRepeating()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientation = ((orientation + 45) / 90 * 90) % 360
            }
        }

        for (o in discoverOptions()) candidates.getOrPut(o.fps) { mutableListOf() }.add(o)
        for (list in candidates.values) list.sortWith(compareBy<Option>({ !it.highSpeed }, { sizeScore(it.size) }))
        rebuildOptions()
        if (options.isEmpty()) {
            b.status.text = "Este celular não libera gravação acima de 30 fps para outros apps. " +
                "Câmera lenta indisponível."
            b.record.isEnabled = false
        } else {
            selectOption(options.firstOrNull { it.fps == 120 } ?: options.first(), reconfigure = false)
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        thread = HandlerThread("slowmo-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        openAttempts = 0
        if (options.isEmpty()) return
        if (b.texture.isAvailable) {
            openCamera()
        } else {
            b.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    openCamera()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemUi.fullscreen(this)
    }

    override fun onPause() {
        if (recording) stopRecording()
        closeCamera()
        orientationListener.disable()
        thread?.quitSafely()
        thread = null
        handler = null
        super.onPause()
    }

    // ---------------------------------------------------------------- descoberta de modos

    private fun discoverOptions(): List<Option> {
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val found = mutableListOf<Option>()

            if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                for (size in map.highSpeedVideoSizes) {
                    for (r in map.getHighSpeedVideoFpsRangesFor(size)) {
                        if (r.lower == r.upper && r.upper > PLAYBACK_FPS) found += Option(id, size, r.upper, true)
                    }
                }
            }
            // Modo comum (sessão normal) sempre entra como última alternativa.
            run {
                val maxFps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.lower == it.upper }?.maxOfOrNull { it.upper } ?: PLAYBACK_FPS
                val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
                if (maxFps > PLAYBACK_FPS && sizes.isNotEmpty()) {
                    found += Option(id, pickSize(sizes.toList()), maxFps, false)
                }
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun sizeScore(s: Size) = when {
        s.width == 1280 && s.height == 720 -> 0
        s.width == 1920 && s.height == 1080 -> 1
        else -> 2 + kotlin.math.abs(s.width * s.height - 1280 * 720) / 1000
    }

    private fun pickSize(sizes: List<Size>) = sizes.minByOrNull { sizeScore(it) }!!

    private fun rebuildOptions() {
        options = candidates.values.mapNotNull { it.firstOrNull() }
        buildOptionButtons()
    }

    private fun buildOptionButtons() {
        b.fpsOptions.removeAllViews()
        val dp = resources.displayMetrics.density
        for (opt in options) {
            val tv = TextView(this).apply {
                text = opt.label
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.pill)
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                tag = opt.fps
                setOnClickListener {
                    val current = candidates[opt.fps]?.firstOrNull()
                    if (!recording && current != null) selectOption(current, reconfigure = true)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
            b.fpsOptions.addView(tv, lp)
        }
    }

    private fun selectOption(opt: Option, reconfigure: Boolean) {
        selected = opt
        for (i in 0 until b.fpsOptions.childCount) {
            val v = b.fpsOptions.getChildAt(i)
            v.isSelected = v.tag == opt.fps
        }
        val ch = manager.getCameraCharacteristics(opt.cameraId)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxZoom = (if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper else null)
            ?: ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        maxZoom = maxZoom.coerceIn(1f, 10f)
        zoom = zoom.coerceIn(1f, maxZoom)
        // Tela em pé: o vídeo (deitado no sensor) aparece girado.
        b.texture.setAspectRatio(opt.size.height, opt.size.width)
        b.status.text = "Abrindo ${opt.label}…"
        if (reconfigure && device != null) {
            if (device?.id != opt.cameraId) { closeCamera(); openCamera() } else createSession()
        }
    }

    /**
     * O modo atual não funcionou (o celular anuncia mas recusa). Tenta outro tamanho com a mesma
     * taxa; se não houver, desativa essa taxa e volta para a maior taxa menor que funcione.
     */
    private fun onModeFailed(reason: String) {
        val failed = selected ?: return
        if (recording) stopRecording()
        closeCamera()
        val list = candidates[failed.fps]
        list?.remove(failed)
        val sameFps = list?.firstOrNull()
        if (sameFps != null) {
            selectOption(sameFps, reconfigure = false)
            b.status.text = "Tentando ${sameFps.label}…"
            reopenSoon()
            return
        }
        candidates.remove(failed.fps)
        rebuildOptions()
        val fallback = options.filter { it.fps < failed.fps }.maxByOrNull { it.fps } ?: options.lastOrNull()
        if (fallback == null) {
            b.status.text = "Nenhum modo de câmera lenta funcionou neste celular ($reason)."
            b.record.isEnabled = false
            return
        }
        selectOption(fallback, reconfigure = false)
        b.status.text = "${failed.fps} fps não é liberado pelo celular para outros apps ($reason). " +
            "Usando ${fallback.fps} fps."
        reopenSoon()
    }

    private fun reopenSoon() {
        b.root.postDelayed({ if (handler != null && device == null) openCamera() }, 500)
    }

    private fun cameraErrorText(error: Int) = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "câmera em uso"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "muitas câmeras abertas"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "câmera bloqueada pelo sistema"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "falha da câmera"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "falha do serviço de câmera"
        else -> "código $error"
    }

    // ---------------------------------------------------------------- câmera

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val opt = selected ?: return
        val h = handler ?: return
        val gen = ++generation
        try {
            manager.openCamera(opt.cameraId, object : CameraDevice.StateCallback() {
                private var opened = false
                override fun onOpened(d: CameraDevice) {
                    opened = true
                    if (gen != generation) { d.close(); return }
                    runOnUiThread {
                        if (gen != generation || handler == null) { d.close(); return@runOnUiThread }
                        device = d
                        openAttempts = 0
                        createSession()
                    }
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    runOnUiThread { if (device == d) device = null }
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    val wasConfiguring = opened && !framesArriving
                    runOnUiThread {
                        if (device == d) device = null
                        if (gen != generation) return@runOnUiThread
                        when {
                            // Câmera ainda sendo liberada pela tela de fotos: tenta de novo.
                            !opened && openAttempts < 5 -> {
                                openAttempts++
                                b.status.text = "Aguardando câmera… (${cameraErrorText(error)})"
                                b.root.postDelayed({ if (handler != null && device == null) openCamera() }, 600)
                            }
                            wasConfiguring -> onModeFailed(cameraErrorText(error))
                            else -> b.status.text = "Erro na câmera: ${cameraErrorText(error)}"
                        }
                    }
                }
            }, h)
        } catch (e: Exception) {
            b.status.text = "Erro ao abrir a câmera: ${e.message}"
        }
    }

    private fun closeCamera() {
        generation++
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        previewSurface?.release()
        previewSurface = null
        recorderSurface?.release()
        recorderSurface = null
    }

    private fun newRecorder(output: File, opt: Option, surface: Surface): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(output.absolutePath)
        r.setVideoEncodingBitRate(if (opt.size.height >= 1080) 20_000_000 else 12_000_000)
        // Captura em alta taxa, arquivo marcado para tocar a 30 fps => vídeo lento.
        r.setCaptureRate(opt.fps.toDouble())
        r.setVideoFrameRate(PLAYBACK_FPS)
        r.setVideoSize(opt.size.width, opt.size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setInputSurface(surface)
        r.setOrientationHint((sensorOrientation + deviceOrientation) % 360)
        r.prepare()
        return r
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val opt = selected ?: return
        val d = device ?: return
        val st = b.texture.surfaceTexture ?: return
        runCatching { session?.close() }
        session = null
        framesArriving = false
        val gen = generation

        st.setDefaultBufferSize(opt.size.width, opt.size.height)
        previewSurface?.release()
        previewSurface = Surface(st)

        // Superfície persistente do gravador: a sessão já nasce com ela, então começar a
        // gravar não precisa reabrir a câmera.
        recorderSurface?.release()
        val persistent = MediaCodec.createPersistentInputSurface()
        recorderSurface = persistent
        try {
            val dummy = File(cacheDir, "slowmo_dummy.mp4")
            newRecorder(dummy, opt, persistent).release()
            dummy.delete()
        } catch (e: Exception) {
            onModeFailed("gravador recusou")
            return
        }

        val targets = listOf(previewSurface!!, persistent)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    session = s
                    updateRepeating()
                    // Se nenhum quadro chegar, o modo não funciona de verdade neste aparelho.
                    b.root.postDelayed({
                        if (gen == generation && !framesArriving) onModeFailed("sem imagem")
                    }, 3000)
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                runOnUiThread { if (gen == generation) onModeFailed("configuração recusada") }
            }
        }
        try {
            if (opt.highSpeed) d.createConstrainedHighSpeedCaptureSession(targets, callback, handler)
            else d.createCaptureSession(targets, callback, handler)
        } catch (e: Exception) {
            onModeFailed("configuração recusada")
        }
    }

    private val frameCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult
        ) {
            if (!framesArriving) {
                framesArriving = true
                runOnUiThread { selected?.let { b.status.text = statusFor(it) } }
            }
        }
    }

    private fun statusFor(opt: Option) =
        if (opt.highSpeed) "Câmera lenta: ${opt.factor}x (${opt.fps} fps)"
        else "Câmera lenta: ${opt.factor}x (alta velocidade não liberada neste celular)"

    private fun updateRepeating() {
        val s = session ?: return
        val d = device ?: return
        val opt = selected ?: return
        val preview = previewSurface ?: return
        try {
            val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(preview)
            if (recording) recorderSurface?.let { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(opt.fps, opt.fps))
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (torchOn) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
            )
            applyZoom(builder)
            if (s is CameraConstrainedHighSpeedCaptureSession) {
                s.setRepeatingBurst(s.createHighSpeedRequestList(builder.build()), frameCallback, handler)
            } else {
                s.setRepeatingRequest(builder.build(), frameCallback, handler)
            }
        } catch (e: Exception) {
            if (!framesArriving) onModeFailed("pedido recusado") else b.status.text = "Erro na câmera: ${e.message}"
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= 30) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val a = activeArray ?: return
            val w = (a.width() / zoom).toInt()
            val h = (a.height() / zoom).toInt()
            val l = a.left + (a.width() - w) / 2
            val t = a.top + (a.height() - h) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
        }
    }

    // ---------------------------------------------------------------- gravação

    private fun startRecording() {
        val opt = selected ?: return
        val surface = recorderSurface ?: return
        if (session == null) return
        val target = Storage.newFile(this, "VIDEO_LENTO_${opt.fps}fps", "mp4")
        val tmp = Storage.tempFor(target)
        try {
            recorder = newRecorder(tmp, opt, surface)
            updateRepeating()
            recorder!!.start()
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            tmp.delete()
            updateRepeating()
            Toast.makeText(this, "Não foi possível gravar: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        recordingTmp = tmp
        recordingTarget = target
        recordStart = SystemClock.elapsedRealtime()
        b.record.setBackgroundResource(R.drawable.record_active)
        setOptionsEnabled(false)
        tickTimer()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        var ok = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            ok = false // gravação curta demais
        }
        r.release()
        updateRepeating()
        b.record.setBackgroundResource(R.drawable.record_idle)
        b.timer.text = ""
        setOptionsEnabled(true)

        val tmp = recordingTmp
        val target = recordingTarget
        recordingTmp = null
        recordingTarget = null
        if (tmp == null || target == null) return
        if (ok && tmp.length() > 0 && tmp.renameTo(target)) {
            Toast.makeText(this, "Vídeo salvo ✔", Toast.LENGTH_SHORT).show()
            UploadScheduler.enqueueNow(this)
        } else {
            tmp.delete()
            Toast.makeText(this, "Vídeo muito curto, não foi salvo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setOptionsEnabled(enabled: Boolean) {
        for (i in 0 until b.fpsOptions.childCount) b.fpsOptions.getChildAt(i).isEnabled = enabled
        b.back.isEnabled = enabled
    }

    private fun tickTimer() {
        if (!recording) return
        val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
        b.timer.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
        b.timer.postDelayed({ tickTimer() }, 500)
    }
}
