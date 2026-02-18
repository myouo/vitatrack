package com.example.healthanomaly.presentation.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.healthanomaly.R
import com.example.healthanomaly.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Settings fragment for configuring thresholds and exporting data.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SettingsViewModel by viewModels()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupSliders()
        setupButtons()
        observeState()
    }
    
    /**
     * Set up threshold sliders.
     */
    private fun setupSliders() {
        // HR High Threshold
        binding.seekbarHrHigh.max = 200
        binding.seekbarHrHigh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvHrHighValue.text = getString(R.string.threshold_bpm, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { viewModel.setHrHighThreshold(it) }
            }
        })
        
        // HR Low Threshold
        binding.seekbarHrLow.max = 80
        binding.seekbarHrLow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvHrLowValue.text = getString(R.string.threshold_bpm, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { viewModel.setHrLowThreshold(it + 20) }
            }
        })
        
        // Step Freq High Threshold
        binding.seekbarStepFreqHigh.max = 50
        binding.seekbarStepFreqHigh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = progress / 10f
                    binding.tvStepFreqHighValue.text = getString(R.string.threshold_hz, value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { viewModel.setStepFreqHighThreshold(it / 10f) }
            }
        })
        
        // Step Freq Low Threshold
        binding.seekbarStepFreqLow.max = 20
        binding.seekbarStepFreqLow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = progress / 10f
                    binding.tvStepFreqLowValue.text = getString(R.string.threshold_hz, value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { viewModel.setStepFreqLowThreshold(it / 10f) }
            }
        })
    }
    
    /**
     * Set up button click listeners.
     */
    private fun setupButtons() {
        // Fall detection toggle
        binding.switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFallDetectionEnabled(isChecked)
        }
        
        // Export button
        binding.btnExport.setOnClickListener {
            viewModel.exportData()
        }
    }
    
    /**
     * Observe ViewModel state.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    binding.seekbarHrHigh.progress = state.hrHighThreshold
                    binding.tvHrHighValue.text = getString(R.string.threshold_bpm, state.hrHighThreshold)
                    
                    binding.seekbarHrLow.progress = state.hrLowThreshold - 20
                    binding.tvHrLowValue.text = getString(R.string.threshold_bpm, state.hrLowThreshold)
                    
                    binding.seekbarStepFreqHigh.progress = (state.stepFreqHighThreshold * 10).toInt()
                    binding.tvStepFreqHighValue.text = getString(R.string.threshold_hz, state.stepFreqHighThreshold)
                    
                    binding.seekbarStepFreqLow.progress = (state.stepFreqLowThreshold * 10).toInt()
                    binding.tvStepFreqLowValue.text = getString(R.string.threshold_hz, state.stepFreqLowThreshold)
                    
                    binding.switchFallDetection.isChecked = state.isFallDetectionEnabled
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
