package com.gyrowallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gyrowallpaper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAurora.setOnClickListener      { setWallpaper(AuroraWallpaperService::class.java) }
        binding.btnDeepspace.setOnClickListener   { setWallpaper(DeepSpaceWallpaperService::class.java) }
        binding.btnLava.setOnClickListener        { setWallpaper(LavaFlowWallpaperService::class.java) }
        binding.btnAllWallpapers.setOnClickListener {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun setWallpaper(serviceClass: Class<*>) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, serviceClass)
            )
        }
        startActivity(intent)
    }
}
