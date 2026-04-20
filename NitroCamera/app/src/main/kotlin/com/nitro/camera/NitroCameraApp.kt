package com.nitro.camera

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class NitroCameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) {
            Log.e("NitroCamera", "OpenCV failed to initialise")
        }
    }
}
