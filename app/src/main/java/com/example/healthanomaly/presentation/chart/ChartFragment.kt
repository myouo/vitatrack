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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chart fragment showing real-time data visualization.
 */
@AndroidEntryPoint
class ChartFragment : Fragment() {
    
    private var _binding: FragmentChartBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ChartViewModel by viewModels()
    
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
        setupLineChart(binding.chartHeartRate, getString(R.string.chart_heart_rate))
        
        // Accelerometer RMS Chart
        setupLineChart(binding.chartAccel, getString(R.string.chart_accel))
        
        // Step Frequency Chart
        setupLineChart(binding.chartStepFreq, getString(R.string.chart_step_freq))
    }
    
    /**
     * Configure a line chart.
     */
    private fun setupLineChart(chart: LineChart, title: String) {
        chart.apply {
            description.text = ""
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            
            // X-axis
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                valueFormatter = TimeAxisFormatter()
                granularity = 1f
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
            
            // Animation
            animateX(500)
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
        // Update current values display
        binding.tvCurrentHr.text = state.currentHeartRate?.let {
            getString(R.string.heart_rate_value, it)
        } ?: getString(R.string.heart_rate_none)
        
        binding.tvCurrentStepFreq.text = state.currentStepFreq?.let {
            getString(R.string.step_freq_value, String.format("%.1f", it))
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
        }
        
        if (hrData.isEmpty()) {
            binding.chartHeartRate.clear()
            return
        }
        
        val dataSet = LineDataSet(hrData, getString(R.string.chart_heart_rate)).apply {
            color = Color.RED
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        
        binding.chartHeartRate.data = LineData(dataSet)
        binding.chartHeartRate.invalidate()
    }
    
    /**
     * Update accelerometer chart.
     */
    private fun updateAccelChart(windows: List<FeatureWindow>) {
        val accelData = windows.map { window ->
            Entry(window.timestampMs.toFloat(), window.rmsAccel)
        }
        
        if (accelData.isEmpty()) {
            binding.chartAccel.clear()
            return
        }
        
        val dataSet = LineDataSet(accelData, getString(R.string.chart_accel)).apply {
            color = Color.BLUE
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        
        binding.chartAccel.data = LineData(dataSet)
        binding.chartAccel.invalidate()
    }
    
    /**
     * Update step frequency chart.
     */
    private fun updateStepFreqChart(windows: List<FeatureWindow>) {
        val stepData = windows.map { window ->
            Entry(window.timestampMs.toFloat(), window.stepFreqHz)
        }
        
        if (stepData.isEmpty()) {
            binding.chartStepFreq.clear()
            return
        }
        
        val dataSet = LineDataSet(stepData, getString(R.string.chart_step_freq)).apply {
            color = Color.GREEN
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        
        binding.chartStepFreq.data = LineData(dataSet)
        binding.chartStepFreq.invalidate()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    private class TimeAxisFormatter : ValueFormatter() {
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        
        override fun getFormattedValue(value: Float): String {
            return dateFormat.format(Date(value.toLong()))
        }
    }
}
        
