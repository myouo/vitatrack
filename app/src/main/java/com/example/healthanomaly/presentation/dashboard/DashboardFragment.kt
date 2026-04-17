package com.example.healthanomaly.presentation.dashboard

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
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
import com.example.healthanomaly.presentation.results.BatchResultsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()
    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        viewModel.loadDatasetFolder(uri)
    }
    private val openZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        viewModel.loadDatasetZip(uri)
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
                viewModel.stopEvaluation()
            } else {
                openSourceChooser()
            }
        }
        binding.btnBleConnect.setOnClickListener {
            BatchResultsActivity.start(requireContext())
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.completedReports.collectLatest {
                    BatchResultsActivity.start(requireContext())
                }
            }
        }
    }

    private fun updateUI(state: DashboardUiState) {
        binding.btnStartStop.text = if (state.isCollecting) {
            getString(R.string.stop_evaluation)
        } else {
            getString(R.string.load_dataset)
        }

        val activeIndex = if (state.isCollecting && state.totalSamples > 0) {
            minOf(state.totalSamples, state.completedSamples + 1)
        } else {
            state.completedSamples
        }
        binding.tvCollectionStatus.text = if (state.isCollecting) {
            getString(R.string.status_running_dataset, activeIndex, state.totalSamples)
        } else {
            getString(R.string.status_waiting_dataset)
        }

        binding.tvBleStatus.text = if (state.isCollecting && state.currentSampleName != null) {
            getString(
                R.string.dataset_sample_label,
                state.currentSampleName
            )
        } else if (state.datasetName != null) {
            getString(
                R.string.dataset_loaded,
                state.datasetName
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
        binding.btnBleConnect.visibility = if (state.hasResults && !state.isCollecting) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.btnBleConnect.text = getString(R.string.view_results)
    }

    private fun updateIndicator(target: View, isActive: Boolean) {
        val colorRes = if (isActive) R.color.status_active else R.color.status_inactive
        target.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), colorRes)
        )
    }

    private fun openSourceChooser() {
        val options = arrayOf(
            getString(R.string.dataset_source_folder),
            getString(R.string.dataset_source_zip)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dataset_source_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFolderLauncher.launch(null)
                    1 -> openZipLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
            .show()
    }

    private fun persistReadPermission(uri: android.net.Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
