package com.example.healthanomaly.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.healthanomaly.R
import com.example.healthanomaly.core.PreferencesManager
import com.example.healthanomaly.databinding.ActivityMainBinding
import com.example.healthanomaly.presentation.dashboard.DashboardFragment
import com.example.healthanomaly.presentation.chart.ChartFragment
import com.example.healthanomaly.presentation.events.EventsFragment
import com.example.healthanomaly.presentation.settings.SettingsFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main activity with bottom navigation for the four main screens.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @Inject
    lateinit var preferencesManager: PreferencesManager

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results
        val allGranted = permissions.all { it.value }
        if (!allGranted) {
            // Show explanation or handle denied permissions
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super.onCreate()
        applyTheme()

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
        requestPermissions()

        // Show dashboard by default
        if (savedInstanceState == null) {
            showFragment(DashboardFragment())
        }
    }

    /**
     * Apply the saved theme preference.
     */
    private fun applyTheme() {
        try {
            val isDarkTheme = preferencesManager.isDarkThemeEnabled()
            setTheme(
                if (isDarkTheme) R.style.Theme_HealthAnomaly_Dark
                else R.style.Theme_HealthAnomaly_Light
            )
        } catch (e: Exception) {
            setTheme(R.style.Theme_HealthAnomaly_Light)
        }
    }
    
    /**
     * Set up bottom navigation.
     */
    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    showFragment(DashboardFragment())
                    true
                }
                R.id.nav_chart -> {
                    showFragment(ChartFragment())
                    true
                }
                R.id.nav_events -> {
                    showFragment(EventsFragment())
                    true
                }
                R.id.nav_settings -> {
                    showFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
    }
    
    /**
     * Show a fragment in the container.
     */
    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
    
    /**
     * Request required permissions.
     */
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
