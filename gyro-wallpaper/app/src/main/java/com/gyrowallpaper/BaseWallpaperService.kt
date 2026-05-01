package com.gyrowallpaper

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

abstract class BaseWallpaperService : WallpaperService() {

    abstract fun createRenderer(): ShaderRenderer

    override fun onCreateEngine(): Engine = GyroEngine()

    inner class GyroEngine : Engine() {

        private val gyro = GyroscopeManager(this@BaseWallpaperService)
        private var glThread: GLThread? = null

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            val renderer = createRenderer()
            glThread = GLThread(holder, gyro, renderer).also { it.start() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            glThread?.setSize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            glThread?.quit()
            glThread = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                gyro.start()
                glThread?.resume()
            } else {
                glThread?.pause()
                gyro.stop()
            }
        }

        override fun onDestroy() {
            glThread?.quit()
            glThread = null
            gyro.stop()
            super.onDestroy()
        }
    }
}
