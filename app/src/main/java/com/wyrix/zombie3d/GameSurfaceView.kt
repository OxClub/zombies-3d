package com.wyrix.zombie3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.widget.FrameLayout

class GameSurfaceView(context: Context) : FrameLayout(context) {

    private val sfx = SoundEngine(context)
    private val engine = GameEngine(context, sfx)
    private val glView = GLSurfaceView(context)
    private val hudView = HudOverlayView(context, engine)

    init {
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(GameRenderer(context, engine) {
            hudView.postInvalidate()
        })
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hudView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun onResume() {
        glView.onResume()
        sfx.resumeMusic()
    }

    fun onPause() {
        sfx.pauseMusic()
        glView.onPause()
    }

    fun onDestroy() {
        sfx.release()
    }
}
