package com.cameng.app

import android.hardware.camera2.CameraMetadata

/**
 * Estima o tamanho real (mm por pixel) a partir da distância de foco informada
 * pela câmera e das características do sensor/lente:
 *
 *   largura_no_objeto = largura_sensor / zoom * (distância - f) / f
 *
 * A precisão depende do celular (normalmente ±10-20%). Para medidas melhores,
 * use o modo "calibrada" com um objeto de tamanho conhecido.
 */
class ScaleEstimator(
    /** Largura (lado maior) da área ativa do sensor em mm. */
    private val sensorLongSideMm: Float,
    private val focalLengthMm: Float,
    private val focusCalibration: Int,
) {
    @Volatile var focusDiopters: Float? = null

    val supported: Boolean
        get() = focusCalibration != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED &&
            sensorLongSideMm > 0f && focalLengthMm > 0f

    val distanceMm: Float?
        get() {
            val d = focusDiopters ?: return null
            if (d <= 0.05f) return null // "infinito" (> 20 m)
            return 1000f / d
        }

    /**
     * @param contentLongSidePx tamanho em pixels de tela do lado maior da imagem exibida
     */
    fun mmPerViewPx(zoomRatio: Float, contentLongSidePx: Float): Float? {
        if (!supported || zoomRatio < 1f || contentLongSidePx <= 0f) return null
        val dist = distanceMm ?: return null
        if (dist <= focalLengthMm) return null
        val objectLongSideMm = sensorLongSideMm / zoomRatio * (dist - focalLengthMm) / focalLengthMm
        return objectLongSideMm / contentLongSidePx
    }
}
