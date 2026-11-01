package com.cameng.app

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/** TextureView que mantém a proporção do vídeo (sem esticar a imagem). */
class AspectTextureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextureView(context, attrs) {
    private var ratioW = 0
    private var ratioH = 0

    fun setAspectRatio(width: Int, height: Int) {
        ratioW = width
        ratioH = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(w, h)
        } else if (w.toLong() * ratioH < h.toLong() * ratioW) {
            setMeasuredDimension(w, (w.toLong() * ratioH / ratioW).toInt())
        } else {
            setMeasuredDimension((h.toLong() * ratioW / ratioH).toInt(), h)
        }
    }
}
