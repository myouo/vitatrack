package com.example.healthanomaly.presentation.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.healthanomaly.R
import com.example.healthanomaly.databinding.FragmentDashboardBinding
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.repository.BleDevice
import com.example.healthanomaly.service.DataCollectionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dashboard fragment showing current status and controls.
 */
@AndroidEntryPoint
class DashboardFragment : Fragment() {
    
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: DashboardViewModel by viewModels()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupButtons()
        observeState()
    }
    
    /**
     * Set up button click listeners.
     */
    private fun setupButtons() {
        // Start/Stop collection button
        binding.btnStartStop.setOnClickListener {
            val isCurrentlyCollecting = viewModel.state.value.isCollecting
            
            if (isCurrentlyCollecting) {
                // Stop collection
                val intent = Intent(requireContext(), DataCollectionService::class.java).apply {
                    action = DataCollectionService.ACTION_STOP
                }
                requireContext().startService(intent)
            } else {
                // Start collection
                val intent = Intent(requireContext(), DataCollectionService::class.java).apply {
                    action = DataCollectionService.ACTION_START
                }
                requireContext().startForegroundService(intent)
            }
        }
        
        // BLE connect button
        binding.btnBleConnect.setOnClickListener {
            val state = viewModel.state.value
            
            when {
                state.isBleConnected -> {
                    // Disconnect
                    viewModel.disconnectBle()
                }
                state.connectionState == BleConnectionState.CONNECTING -> {
                    // Currently connecting, do nothing
                }
                else -> {
                    // Start scan and show device picker
                    viewModel.startBleScan()
                    showDevicePicker()
                }
            }
        }
    }
    
    /**
     * Observe ViewModel state.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    updateUI(state)
                }
            }
        }
    }
    
    /**
     * Update UI based on state.
     */
    private fun updateUI(state: DashboardUiState) {
        // Collection status
        binding.btnStartStop.text = if (state.isCollecting) {
            getString(R.string.stop_collection)
        } else {
            getString(R.string.start_collection)
        }
        
        binding.tvCollectionStatus.text = if (state.isCollecting) {
            getString(R.string.status_collecting)
        } else {
            getString(R.string.status_stopped)
        }
        
        // BLE connection status
        binding.btnBleConnect.text = when {
            state.isBleConnected -> getString(R.string.disconnect)
            state.connectionState == BleConnectionState.CONNECTING -> getString(R.string.connecting)
            else -> getString(R.string.connect)
        }
        
        // Heart rate display
        binding.tvHeartRate.text = state.currentHeartRate?.let {
            getString(R.string.heart_rate_value, it)
        } ?: getString(R.string.heart_rate_none)
        
        // Step frequency display
        binding.tvStepFreq.text = state.currentStepFreq?.let {
            getString(R.string.step_freq_value, String.format("%.1f", it))
        } ?: getString(R.string.step_freq_none)
    }
    
    /**
     * Show BLE device picker dialog.
     */
    private fun showDevicePicker() {
        val devices = viewModel.state.value.availableDevices
        if (devices.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_devices_found, Toast.LENGTH_SHORT).show()
            return
        }
        
        val deviceNames = devices.map { it.name ?: it.address }.toTypedArray()
        
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_device)
            .setItems(deviceNames) { _, which ->
                viewModel.connectBle(devices[which].address)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
