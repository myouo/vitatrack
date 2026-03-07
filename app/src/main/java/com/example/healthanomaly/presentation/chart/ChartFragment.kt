package com.example.healthanomaly.presentation.chart

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.healthanomaly.R
import com.example.healthanomaly.databinding.FragmentChartBinding
import com.example.healthanomaly.domain.model.FeatureWindow
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Chart fragment showing real-time data visualization.
 */
@AndroidEntryPoint
class ChartFragment : Fragment() {
    companion object {
        private const val LIVE_WINDOW_MS = 30_000f
    }

    
    private var _binding: FragmentChartBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ChartViewModel by viewModels()
    private val heartRateAxisFormatter = RelativeTimeAxisFormatter()
    private val accelAxisFormatter = RelativeTimeAxisFormatter()
    private val stepAxisFormatter = RelativeTimeAxisFormatter()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChartBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupCharts()
        observeState()
    }
    
    /**
     * Set up chart configurations.
     */
    private fun setupCharts() {
        // Heart Rate Chart
        setupLineChart(binding.chartHeartRate, heartRateAxisFormatter)
        
        // Accelerometer RMS Chart
        setupLineChart(binding.chartAccel, accelAxisFormatter)
        
        // Step Frequency Chart
        setupLineChart(binding.chartStepFreq, stepAxisFormatter)
    }
    
    /**
     * Configure a line chart.
     */
    private fun setupLineChart(chart: LineChart, formatter: RelativeTimeAxisFormatter) {
        chart.apply {
            description.text = ""
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setPinchZoom(false)
            setDrawGridBackground(false)
            setAutoScaleMinMaxEnabled(true)
            setVisibleXRangeMaximum(LIVE_WINDOW_MS)
            
            // X-axis
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                valueFormatter = formatter
                granularity = 5_000f
                axisMinimum = 0f
            }
            
            // Left Y-axis
            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
            }
            
            // Right Y-axis
            axisRight.isEnabled = false
            
            // Legend
            legend.isEnabled = false
        }
    }
    
    /**
     * Observe ViewModel state.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    updateCharts(state)
                }
            }
        }
    }
    
    /**
     * Update charts with new data.
     */
    private fun updateCharts(state: ChartUiState) {
        val baseTimestampMs = state.featureWindows.firstOrNull()?.timestampMs ?: 0L
        heartRateAxisFormatter.baseTimestampMs = baseTimestampMs
        accelAxisFormatter.baseTimestampMs = baseTimestampMs
        stepAxisFormatter.baseTimestampMs = baseTimestampMs

        // Update current values display
        binding.tvCurrentHr.text = state.currentHeartRate?.let {
            getString(R.string.heart_rate_value, it)
        } ?: getString(R.string.heart_rate_none)
        
        binding.tvCurrentStepFreq.text = state.currentStepFreq?.let {
            getString(R.string.step_freq_value, it)
        } ?: getString(R.string.step_freq_none)
        
        // Update heart rate chart
        updateHeartRateChart(state.featureWindows)
        
        // Update accelerometer chart
        updateAccelChart(state.featureWindows)
        
        // Update step frequency chart
        updateStepFreqChart(state.featureWindows)
    }
    
    /**
     * Update heart rate chart.
     */
    private fun updateHeartRateChart(windows: List<FeatureWindow>) {
        val hrData = windows.mapNotNull { window ->
            window.heartRateBpm?.let { hr ->
                Entry(window.timestampMs.toFloat(), hr.toFloat())
            }
        }.filter(::isValidEntry)
        
        if (hrData.isEmpty()) {
            binding.chartHeartRate.clear()
            return
        }
        
        renderLiveChart(
            chart = binding.chartHeartRate,
            entries = hrData,
            label = getString(R.string.chart_heart_rate),
            color = Color.RED
        )
    }
    
    /**
     * Update accelerometer chart.
     */
    private fun updateAccelChart(windows: List<FeatureWindow>) {
        val accelData = windows.map { window ->
            Entry(window.timestampMs.toFloat(), window.rmsAccel)
        }.filter(::isValidEntry)
        
        if (accelData.isEmpty()) {
            binding.chartAccel.clear()
            return
        }
        
        renderLiveChart(
            chart = binding.chartAccel,
            entries = accelData,
            label = getString(R.string.chart_accel),
            color = Color.BLUE
        )
    }
    
    /**
     * Update step frequency chart.
     */
    private fun updateStepFreqChart(windows: List<FeatureWindow>) {
        val stepData = windows.map { window ->
            Entry(window.timestampMs.toFloat(), window.stepFreqHz)
        }.filter(::isValidEntry)
        
        if (stepData.isEmpty()) {
            binding.chartStepFreq.clear()
            return
        }
        
        renderLiveChart(
            chart = binding.chartStepFreq,
            entries = stepData,
            label = getString(R.string.chart_step_freq),
            color = Color.GREEN
        )
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun isValidEntry(entry: Entry): Boolean {
        return entry.x.isFinite() && entry.y.isFinite()
    }

    private fun renderLiveChart(
        chart: LineChart,
        entries: List<Entry>,
        label: String,
        color: Int
    ) {
        val dataSet = LineDataSet(entries, label).apply {
            this.color = color
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawHighlightIndicators(false)
            mode = LineDataSet.Mode.LINEAR
        }

        chart.data = LineData(dataSet)
        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(LIVE_WINDOW_MS)
        chart.moveViewToX((entries.last().x - LIVE_WINDOW_MS).coerceAtLeast(0f))
        chart.invalidate()
    }

    private class RelativeTimeAxisFormatter : ValueFormatter() {
        var baseTimestampMs: Long = 0L

        override fun getFormattedValue(value: Float): String {
            val elapsedSeconds = ((value.toLong() - baseTimestampMs).coerceAtLeast(0L)) / 1000L
            val minutes = elapsedSeconds / 60L
            val seconds = elapsedSeconds % 60L
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}
