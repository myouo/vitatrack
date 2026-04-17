package com.example.healthanomaly.data.dataset

import android.net.Uri
import com.example.healthanomaly.domain.model.AnomalyType
import java.io.File

const val BATCH_NONE_LABEL = "NONE"

val BATCH_CORE_LABELS = setOf(
    AnomalyType.HEART_RATE_HIGH.name,
    AnomalyType.HEART_RATE_LOW.name,
    AnomalyType.STEP_FREQ_HIGH.name,
    AnomalyType.STEP_FREQ_LOW.name,
    AnomalyType.GAIT_SUDDEN_CHANGE.name
)

data class BatchDataset(
    val datasetName: String,
    val version: String,
    val intervalMs: Long,
    val durationMs: Long,
    val predictionRule: String,
    val samples: List<BatchDatasetSample>,
    val extractionDir: File? = null
)

data class BatchDatasetSample(
    val sampleId: String,
    val fileName: String,
    val expectedLabel: String,
    val difficulty: String,
    val templateType: String,
    val seed: Long,
    val contentUri: Uri? = null,
    val extractedFile: File? = null
) {
    val displayName: String
        get() = extractedFile?.name ?: fileName
}

data class BatchSampleResult(
    val sampleId: String,
    val displayName: String,
    val expectedLabel: String,
    val predictedLabel: String,
    val matched: Boolean,
    val topSeverity: Int,
    val detectedTypes: List<String>,
    val elapsedMs: Long,
    val error: String? = null
)

data class BatchClassMetric(
    val label: String,
    val precision: Double,
    val recall: Double,
    val support: Int
)

data class BatchConfusionRow(
    val expectedLabel: String,
    val predictedCounts: Map<String, Int>
)

data class BatchEvaluationReport(
    val datasetName: String,
    val totalSamples: Int,
    val completedSamples: Int,
    val accuracy: Double,
    val cancelled: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
    val predictedNoneCount: Int,
    val classMetrics: List<BatchClassMetric>,
    val confusionRows: List<BatchConfusionRow>,
    val sampleResults: List<BatchSampleResult>
)

data class BatchEvaluationExportPaths(
    val jsonPath: String?,
    val csvPath: String?
)
