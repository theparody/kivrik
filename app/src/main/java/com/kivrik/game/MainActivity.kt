package com.kivrik.game

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/** Tek ekranlı native Activity: içerik tamamen GameView (Canvas) ile çizilir. */
class MainActivity : Activity() {

    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GameView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        view.onHostResume()
    }

    override fun onPause() {
        view.onHostPause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
