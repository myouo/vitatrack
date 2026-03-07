package com.example.healthanomaly.presentation.dashboard

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.healthanomaly.R
import com.example.healthanomaly.databinding.FragmentDashboardBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.loadStreamFile(uri, resolveDisplayName(uri))
    }

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

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.state.value.isCollecting) {
                viewModel.stopPlayback()
            } else {
                openDocumentLauncher.launch("*/*")
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    updateUI(state)
                    state.error?.let { errorMessage ->
                        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun updateUI(state: DashboardUiState) {
        binding.btnStartStop.text = if (state.isCollecting) {
            getString(R.string.stop_playback)
        } else {
            getString(R.string.load_stream_file)
        }

        binding.tvCollectionStatus.text = if (state.isCollecting) {
            getString(R.string.status_playing_file)
        } else {
            getString(R.string.status_waiting_file)
        }

        binding.tvBleStatus.text = if (state.isCollecting && state.isSourceActive) {
            getString(
                R.string.stream_source_loaded,
                state.sourceName ?: getString(R.string.stream_default_name)
            )
        } else {
            getString(R.string.stream_source_idle)
        }

        binding.tvHeartRate.text = state.currentHeartRate?.let {
            getString(R.string.heart_rate_value, it)
        } ?: getString(R.string.heart_rate_none)

        binding.tvStepFreq.text = state.currentStepFreq?.let {
            getString(R.string.step_freq_value, it)
        } ?: getString(R.string.step_freq_none)

        binding.tvMotionIntensity.text = if ((state.currentStepFreq ?: 0f) > 0.5f) {
            getString(R.string.motion_active)
        } else {
            getString(R.string.motion_inactive)
        }

        updateIndicator(binding.statusIndicator, state.isCollecting)
        updateIndicator(binding.bleStatusIndicator, state.isCollecting && state.isSourceActive)
    }

    private fun updateIndicator(target: View, isActive: Boolean) {
        val colorRes = if (isActive) R.color.status_active else R.color.status_inactive
        target.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), colorRes)
        )
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }
            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex < 0) null else cursor.getString(columnIndex)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
