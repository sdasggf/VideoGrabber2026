package com.weig.videograbber

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.weig.videograbber.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) switchTo(BrowserFragment(), R.id.nav_browser)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_browser -> switchTo(BrowserFragment(), R.id.nav_browser)
                R.id.nav_downloads -> switchTo(DownloadsFragment(), R.id.nav_downloads)
                else -> false
            }
        }

        requestNotificationPermission()
    }

    private fun switchTo(fragment: Fragment, id: Int): Boolean {
        if (supportFragmentManager.findFragmentById(binding.container.id)?.javaClass == fragment.javaClass) {
            return true
        }
        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, fragment)
            .commit()
        binding.bottomNav.menu.findItem(id)?.isChecked = true
        return true
    }

    /** Android 13+ 需授权通知，否则前台服务的进度通知不显示。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
