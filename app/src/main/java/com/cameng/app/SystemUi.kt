package com.cameng.app

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Tela cheia e espaçamento para não ficar atrás das barras do sistema / câmera frontal. */
object SystemUi {

    /** Esconde barra de status e de navegação (aparecem deslizando da borda). */
    fun fullscreen(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * Aplica padding extra em [top] e [bottom] igual ao espaço ocupado pelas barras do sistema
     * e pelo recorte da câmera, preservando o padding original definido no layout.
     */
    fun padForInsets(root: View, top: View?, bottom: View?) {
        val topBase = top?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        val botBase = bottom?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (top != null && top === bottom && topBase != null) {
                top.setPadding(topBase[0] + i.left, topBase[1] + i.top, topBase[2] + i.right, topBase[3] + i.bottom)
                return@setOnApplyWindowInsetsListener insets
            }
            if (top != null && topBase != null) {
                top.setPadding(topBase[0] + i.left, topBase[1] + i.top, topBase[2] + i.right, topBase[3])
            }
            if (bottom != null && botBase != null) {
                bottom.setPadding(botBase[0] + i.left, botBase[1], botBase[2] + i.right, botBase[3] + i.bottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
