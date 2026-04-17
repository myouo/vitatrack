package com.example.healthanomaly.presentation.results

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.healthanomaly.R
import com.example.healthanomaly.core.BatchEvaluationManager
import com.example.healthanomaly.data.dataset.BatchEvaluationReport
import com.example.healthanomaly.databinding.ActivityBatchResultsBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BatchResultsActivity : AppCompatActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, BatchResultsActivity::class.java))
        }
    }

    @Inject lateinit var batchEvaluationManager: BatchEvaluationManager

    private lateinit var binding: ActivityBatchResultsBinding
    private val adapter = BatchResultsAdapter()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerViewResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewResults.adapter = adapter
        binding.btnClose.setOnClickListener { finish() }
        binding.btnExport.setOnClickListener {
            exportReport()
        }

        observeReport()
    }

    private fun observeReport() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                batchEvaluationManager.latestReport.collectLatest { report ->
                    if (report == null) {
                        binding.tvEmptyState.visibility = android.view.View.VISIBLE
                        adapter.submitList(emptyList())
                    } else {
                        binding.tvEmptyState.visibility = android.view.View.GONE
                        renderReport(report)
                    }
                }
            }
        }
    }

    private fun renderReport(report: BatchEvaluationReport) {
        binding.tvDatasetName.text = getString(R.string.batch_report_dataset, report.datasetName)
        binding.tvStartedAt.text = getString(
            R.string.batch_report_started,
            dateFormat.format(Date(report.startedAt))
        )
        binding.tvFinishedAt.text = getString(
            R.string.batch_report_finished,
            dateFormat.format(Date(report.finishedAt))
        )
        binding.tvAccuracy.text = getString(
            R.string.batch_accuracy,
            report.accuracy * 100.0
        )
        binding.tvSummary.text = getString(
            R.string.batch_summary_line,
            report.completedSamples,
            report.totalSamples,
            report.predictedNoneCount,
            getString(
                if (report.cancelled) R.string.batch_status_cancelled else R.string.batch_status_complete
            )
        )
        binding.tvClassMetrics.text = report.classMetrics.joinToString("\n") { metric ->
            "${metric.label}: precision=${"%.2f".format(metric.precision)}, recall=${"%.2f".format(metric.recall)}, support=${metric.support}"
        }
        binding.tvConfusionMatrix.text = report.confusionRows.joinToString("\n") { row ->
            val compactCounts = row.predictedCounts.entries
                .filter { it.value > 0 }
                .joinToString(", ") { "${it.key}=${it.value}" }
                .ifBlank { "no predictions" }
            "${row.expectedLabel} -> $compactCounts"
        }
        adapter.submitList(report.sampleResults)
    }

    private fun exportReport() {
        lifecycleScope.launch {
            val exportPaths = batchEvaluationManager.exportLatestReport()
            if (exportPaths == null || (exportPaths.jsonPath == null && exportPaths.csvPath == null)) {
                Toast.makeText(
                    this@BatchResultsActivity,
                    getString(R.string.batch_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            Toast.makeText(
                this@BatchResultsActivity,
                getString(R.string.batch_export_success),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
