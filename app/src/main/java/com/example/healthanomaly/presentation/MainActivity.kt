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
import com.example.healthanomaly.databinding.ActivityMainBinding
import com.example.healthanomaly.presentation.dashboard.DashboardFragment
import com.example.healthanomaly.presentation.chart.ChartFragment
import com.example.healthanomaly.presentation.events.EventsFragment
import com.example.healthanomaly.presentation.settings.SettingsFragment
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main activity with bottom navigation for the four main screens.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
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
