package com.gyrowallpaper

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.SurfaceHolder

class GLThread(
    private val holder: SurfaceHolder,
    private val gyro: GyroscopeManager,
    private val renderer: ShaderRenderer
) : Thread("GyroWallpaperGL") {

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var pendingWidth = 0
    @Volatile private var pendingHeight = 0
    @Volatile private var sizeChanged = false

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun setSize(w: Int, h: Int) {
        pendingWidth = w
        pendingHeight = h
        sizeChanged = true
    }

    fun pause() { paused = true }

    fun resume() { paused = false }

    fun quit() {
        running = false
        interrupt()
    }

    override fun run() {
        running = true
        if (!initEGL()) { running = false; return }

        renderer.onSurfaceCreated()
        renderer.onSurfaceChanged(pendingWidth, pendingHeight)

        while (running) {
            if (paused) {
                sleep(32)
                continue
            }

            if (sizeChanged) {
                sizeChanged = false
                renderer.onSurfaceChanged(pendingWidth, pendingHeight)
            }

            renderer.tiltX = gyro.tiltX
            renderer.tiltY = gyro.tiltY
            renderer.onDrawFrame()

            val swapped = EGL14.eglSwapBuffers(display, surface)
            if (!swapped) {
                Log.w("GyroWallpaper", "eglSwapBuffers failed: ${EGL14.eglGetError()}")
                sleep(16)
            }
        }

        destroyEGL()
    }

    private fun initEGL(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) return false

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, configs[0]!!, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false

        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        surface = EGL14.eglCreateWindowSurface(display, configs[0]!!, holder.surface, surfAttribs, 0)
        if (surface == EGL14.EGL_NO_SURFACE) return false

        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun destroyEGL() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
