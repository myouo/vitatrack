package com.example.healthanomaly.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.example.healthanomaly.R
import com.example.healthanomaly.data.dataset.BATCH_CORE_LABELS
import com.example.healthanomaly.data.dataset.BATCH_NONE_LABEL
import com.example.healthanomaly.data.dataset.BatchClassMetric
import com.example.healthanomaly.data.dataset.BatchConfusionRow
import com.example.healthanomaly.data.dataset.BatchDataset
import com.example.healthanomaly.data.dataset.BatchDatasetLoader
import com.example.healthanomaly.data.dataset.BatchDatasetSample
import com.example.healthanomaly.data.dataset.BatchEvaluationExportPaths
import com.example.healthanomaly.data.dataset.BatchEvaluationReport
import com.example.healthanomaly.data.dataset.BatchSampleResult
import com.example.healthanomaly.data.stream.FilePlaybackManager
import com.example.healthanomaly.domain.detection.AnomalyDetectionEngine
import com.example.healthanomaly.domain.detection.DetectionConfig
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.usecase.ProcessWindowUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class BatchEvaluationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val filePlaybackManager: FilePlaybackManager,
    private val processWindowUseCase: ProcessWindowUseCase,
    private val preferencesManager: PreferencesManager,
    private val datasetLoader: BatchDatasetLoader
) {
    companion object {
        private const val MAX_LIVE_WINDOWS = 60
        private const val EXPORT_FOLDER = "VitaTrackEvaluations"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        scope.launch {
            handleFatalBatchError(throwable)
        }
    }
    private val exportDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _datasetName = MutableStateFlow<String?>(null)
    val datasetName: StateFlow<String?> = _datasetName.asStateFlow()

    private val _currentSampleName = MutableStateFlow<String?>(null)
    val currentSampleName: StateFlow<String?> = _currentSampleName.asStateFlow()

    private val _completedSamples = MutableStateFlow(0)
    val completedSamples: StateFlow<Int> = _completedSamples.asStateFlow()

    private val _totalSamples = MutableStateFlow(0)
    val totalSamples: StateFlow<Int> = _totalSamples.asStateFlow()

    private val _currentHeartRate = MutableStateFlow<Int?>(null)
    val currentHeartRate: StateFlow<Int?> = _currentHeartRate.asStateFlow()

    private val _currentStepFreq = MutableStateFlow<Float?>(null)
    val currentStepFreq: StateFlow<Float?> = _currentStepFreq.asStateFlow()

    private val _recentFeatureWindows = MutableStateFlow<List<FeatureWindow>>(emptyList())
    val recentFeatureWindows: StateFlow<List<FeatureWindow>> = _recentFeatureWindows.asStateFlow()

    private val _isSourceActive = MutableStateFlow(false)
    val isSourceActive: StateFlow<Boolean> = _isSourceActive.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _latestReport = MutableStateFlow<BatchEvaluationReport?>(null)
    val latestReport: StateFlow<BatchEvaluationReport?> = _latestReport.asStateFlow()

    private val _completedReports = MutableSharedFlow<BatchEvaluationReport>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val completedReports = _completedReports.asSharedFlow()

    private var batchJob: Job? = null
    private var windowProcessingJob: Job? = null
    private var activeDataset: BatchDataset? = null
    private var currentSampleCompletion: CompletableDeferred<SamplePlaybackStatus>? = null
    private var stopRequested = false
    private var lastWindowTime = 0L
    private var lastHeartRate: Int? = null
    private var latestSampleTimestampMs = 0L
    private var detectionEngine = AnomalyDetectionEngine(buildDetectionConfig())

    private val imuDataRingBuffer = ArrayDeque<ImuData>(1000)
    private val currentSampleAnomalies = mutableListOf<AnomalyEvent>()

    init {
        observePlaybackStreams()
    }

    suspend fun startFromFolder(treeUri: android.net.Uri) {
        stopEvaluation()
        val dataset = datasetLoader.loadFromTreeUri(treeUri)
        startBatch(dataset)
    }

    suspend fun startFromZip(zipUri: android.net.Uri) {
        stopEvaluation()
        val dataset = datasetLoader.loadFromZipUri(zipUri)
        startBatch(dataset)
    }

    suspend fun stopEvaluation() {
        if (!_isRunning.value && batchJob == null) {
            return
        }

        stopRequested = true
        currentSampleCompletion?.complete(SamplePlaybackStatus.Cancelled)
        filePlaybackManager.stopPlayback()
        batchJob?.join()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    suspend fun exportLatestReport(): BatchEvaluationExportPaths? {
        val report = latestReport.value ?: return null
        return withContext(Dispatchers.IO) {
            val timestamp = exportDateFormat.format(Date())
            val jsonPath = saveToDownloads(
                filename = "batch_evaluation_$timestamp.json",
                mimeType = "application/json",
                content = buildJsonReport(report)
            )
            val csvPath = saveToDownloads(
                filename = "batch_evaluation_$timestamp.csv",
                mimeType = "text/csv",
                content = buildCsvReport(report)
            )
            BatchEvaluationExportPaths(
                jsonPath = jsonPath,
                csvPath = csvPath
            )
        }
    }

    private fun startBatch(dataset: BatchDataset) {
        activeDataset = dataset
        batchJob = scope.launch(exceptionHandler) {
            runBatch(dataset)
        }
    }

    private suspend fun runBatch(dataset: BatchDataset) {
        resetBatchState(dataset)
        val results = mutableListOf<BatchSampleResult>()
        val startedAt = System.currentTimeMillis()
        var cancelled = false

        try {
            for (sample in dataset.samples) {
                if (stopRequested) {
                    cancelled = true
                    break
                }

                _currentSampleName.value = sample.displayName
                val result = evaluateSample(sample)
                if (result == null) {
                    cancelled = true
                    break
                }

                results.add(result)
                _completedSamples.value = results.size
            }
        } finally {
            val finishedAt = System.currentTimeMillis()
            val finalReport = buildEvaluationReport(
                dataset = dataset,
                results = results,
                startedAt = startedAt,
                finishedAt = finishedAt,
                cancelled = cancelled || stopRequested
            )
            _latestReport.value = finalReport
            finalizeBatch(finalReport)
        }
    }

    private suspend fun evaluateSample(sample: BatchDatasetSample): BatchSampleResult? {
        resetPerSampleState()
        detectionEngine = AnomalyDetectionEngine(buildDetectionConfig())
        currentSampleCompletion = CompletableDeferred()
        val startedAt = SystemClock.elapsedRealtime()

        return try {
            if (sample.contentUri != null) {
                filePlaybackManager.loadStream(sample.contentUri, sample.displayName)
            } else {
                val extractedFile = sample.extractedFile
                    ?: throw IllegalArgumentException("Dataset sample source is missing")
                filePlaybackManager.loadStreamFile(extractedFile, sample.displayName)
            }

            filePlaybackManager.startPlayback()
            startWindowProcessing()

            when (val status = currentSampleCompletion?.await() ?: SamplePlaybackStatus.Completed) {
                SamplePlaybackStatus.Cancelled -> {
                    stopSampleProcessing()
                    null
                }

                SamplePlaybackStatus.Completed -> {
                    processAvailableWindows(latestSampleTimestampMs)
                    stopSampleProcessing()
                    buildSampleResult(
                        sample = sample,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    )
                }

                is SamplePlaybackStatus.Failed -> {
                    processAvailableWindows(latestSampleTimestampMs)
                    stopSampleProcessing()
                    buildSampleResult(
                        sample = sample,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                        error = status.message
                    )
                }
            }
        } catch (throwable: Throwable) {
            stopSampleProcessing()
            buildSampleResult(
                sample = sample,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                error = throwable.message ?: context.getString(R.string.playback_failed)
            )
        } finally {
            currentSampleCompletion = null
        }
    }

    private fun observePlaybackStreams() {
        scope.launch(exceptionHandler) {
            filePlaybackManager.imuDataFlow
                .buffer(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .collectLatest { imuData ->
                    if (!_isRunning.value) {
                        return@collectLatest
                    }

                    latestSampleTimestampMs = imuData.timestampMs
                    synchronized(imuDataRingBuffer) {
                        if (imuDataRingBuffer.size >= 1000) {
                            imuDataRingBuffer.removeFirst()
                        }
                        imuDataRingBuffer.addLast(imuData)
                    }
                }
        }

        scope.launch(exceptionHandler) {
            filePlaybackManager.heartRateFlow.collectLatest { heartRateData ->
                if (!_isRunning.value) {
                    return@collectLatest
                }

                _currentHeartRate.value = heartRateData.heartRateBpm
                lastHeartRate = heartRateData.heartRateBpm
            }
        }

        scope.launch(exceptionHandler) {
            filePlaybackManager.connectionStateFlow.collectLatest { state ->
                _isSourceActive.value = state == BleConnectionState.CONNECTED
            }
        }

        scope.launch(exceptionHandler) {
            filePlaybackManager.playbackCompletedFlow.collectLatest {
                currentSampleCompletion?.complete(SamplePlaybackStatus.Completed)
            }
        }

        scope.launch(exceptionHandler) {
            filePlaybackManager.playbackErrorFlow.collectLatest { message ->
                currentSampleCompletion?.complete(SamplePlaybackStatus.Failed(message))
            }
        }
    }

    private fun startWindowProcessing() {
        windowProcessingJob?.cancel()
        windowProcessingJob = scope.launch(exceptionHandler) {
            while (isActive && _isRunning.value) {
                processAvailableWindows(latestSampleTimestampMs)
                delay(50)
            }
        }
    }

    private suspend fun processAvailableWindows(latestTimestamp: Long) {
        val windowSizeMs = ProcessWindowUseCase.WINDOW_SIZE_MS
        val stepSizeMs = ProcessWindowUseCase.STEP_SIZE_MS

        if (latestTimestamp < windowSizeMs) {
            return
        }

        if (lastWindowTime == 0L) {
            lastWindowTime = windowSizeMs - stepSizeMs
        }

        while (latestTimestamp - lastWindowTime >= stepSizeMs) {
            lastWindowTime += stepSizeMs
            processWindow(lastWindowTime)
        }
    }

    private suspend fun processWindow(windowEndTime: Long) {
        val windowStartTime = windowEndTime - ProcessWindowUseCase.WINDOW_SIZE_MS
        val samples = synchronized(imuDataRingBuffer) {
            imuDataRingBuffer.filter { it.timestampMs in windowStartTime..windowEndTime }
        }
        if (samples.isEmpty()) {
            return
        }

        val window = processWindowUseCase.extractFeatures(
            samples = samples,
            windowEndTimestamp = windowEndTime,
            heartRateBpm = lastHeartRate
        )

        _currentStepFreq.value = window.stepFreqHz
        appendRecentWindow(window)
        val anomalies = detectionEngine.detect(window)
        if (anomalies.isNotEmpty()) {
            currentSampleAnomalies.addAll(anomalies)
        }
    }

    private suspend fun stopSampleProcessing() {
        filePlaybackManager.stopPlayback()
        windowProcessingJob?.cancelAndJoin()
        windowProcessingJob = null
        _isSourceActive.value = false
    }

    private fun buildSampleResult(
        sample: BatchDatasetSample,
        elapsedMs: Long,
        error: String? = null
    ): BatchSampleResult {
        val topEvent = currentSampleAnomalies
            .sortedWith(
                compareByDescending<AnomalyEvent> { it.severity }
                    .thenBy { it.timestampMs }
            )
            .firstOrNull()
        val predictedLabel = topEvent?.type?.name ?: BATCH_NONE_LABEL
        return BatchSampleResult(
            sampleId = sample.sampleId,
            displayName = sample.displayName,
            expectedLabel = sample.expectedLabel,
            predictedLabel = predictedLabel,
            matched = error == null && predictedLabel == sample.expectedLabel,
            topSeverity = topEvent?.severity ?: 0,
            detectedTypes = currentSampleAnomalies.map { it.type.name }.distinct(),
            elapsedMs = elapsedMs,
            error = error
        )
    }

    private fun buildEvaluationReport(
        dataset: BatchDataset,
        results: List<BatchSampleResult>,
        startedAt: Long,
        finishedAt: Long,
        cancelled: Boolean
    ): BatchEvaluationReport {
        val accuracy = if (results.isEmpty()) {
            0.0
        } else {
            results.count { it.matched }.toDouble() / results.size
        }
        val predictedLabels = linkedSetOf<String>().apply {
            addAll(BATCH_CORE_LABELS.sorted())
            add(BATCH_NONE_LABEL)
            results.mapTo(this) { it.predictedLabel }
        }.toList()

        val classMetrics = BATCH_CORE_LABELS.sorted().map { label ->
            val truePositive = results.count { it.expectedLabel == label && it.predictedLabel == label }
            val falsePositive = results.count { it.expectedLabel != label && it.predictedLabel == label }
            val falseNegative = results.count { it.expectedLabel == label && it.predictedLabel != label }
            BatchClassMetric(
                label = label,
                precision = if (truePositive + falsePositive == 0) {
                    0.0
                } else {
                    truePositive.toDouble() / (truePositive + falsePositive)
                },
                recall = if (truePositive + falseNegative == 0) {
                    0.0
                } else {
                    truePositive.toDouble() / (truePositive + falseNegative)
                },
                support = results.count { it.expectedLabel == label }
            )
        }

        val confusionRows = BATCH_CORE_LABELS.sorted().map { expectedLabel ->
            BatchConfusionRow(
                expectedLabel = expectedLabel,
                predictedCounts = predictedLabels.associateWith { predictedLabel ->
                    results.count {
                        it.expectedLabel == expectedLabel &&
                            it.predictedLabel == predictedLabel
                    }
                }
            )
        }

        return BatchEvaluationReport(
            datasetName = dataset.datasetName,
            totalSamples = dataset.samples.size,
            completedSamples = results.size,
            accuracy = accuracy,
            cancelled = cancelled,
            startedAt = startedAt,
            finishedAt = finishedAt,
            predictedNoneCount = results.count { it.predictedLabel == BATCH_NONE_LABEL },
            classMetrics = classMetrics,
            confusionRows = confusionRows,
            sampleResults = results
        )
    }

    private suspend fun finalizeBatch(report: BatchEvaluationReport) {
        windowProcessingJob?.cancelAndJoin()
        windowProcessingJob = null
        batchJob = null
        stopRequested = false
        filePlaybackManager.stopPlayback()
        activeDataset?.let(datasetLoader::cleanup)
        activeDataset = null

        _isRunning.value = false
        _currentSampleName.value = null
        _currentHeartRate.value = null
        _currentStepFreq.value = null
        _isSourceActive.value = false
        _recentFeatureWindows.value = emptyList()

        if (!report.cancelled) {
            _completedReports.emit(report)
        }
    }

    private suspend fun handleFatalBatchError(throwable: Throwable) {
        _errorMessage.value = throwable.message ?: context.getString(R.string.playback_failed)
        val currentReport = _latestReport.value
        if (currentReport == null || !currentReport.cancelled) {
            _latestReport.value = currentReport?.copy(cancelled = true)
        }
        val fallbackReport = currentReport ?: BatchEvaluationReport(
            datasetName = _datasetName.value ?: "Unknown dataset",
            totalSamples = _totalSamples.value,
            completedSamples = _completedSamples.value,
            accuracy = 0.0,
            cancelled = true,
            startedAt = System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
            predictedNoneCount = 0,
            classMetrics = emptyList(),
            confusionRows = emptyList(),
            sampleResults = emptyList()
        )
        _latestReport.value = fallbackReport
        finalizeBatch(fallbackReport)
    }

    private fun resetBatchState(dataset: BatchDataset) {
        stopRequested = false
        _isRunning.value = true
        _datasetName.value = dataset.datasetName
        _currentSampleName.value = null
        _completedSamples.value = 0
        _totalSamples.value = dataset.samples.size
        _errorMessage.value = null
        _latestReport.value = null
        resetPerSampleState()
    }

    private fun resetPerSampleState() {
        lastWindowTime = 0L
        lastHeartRate = null
        latestSampleTimestampMs = 0L
        currentSampleAnomalies.clear()
        _currentHeartRate.value = null
        _currentStepFreq.value = null
        _isSourceActive.value = false
        _recentFeatureWindows.value = emptyList()
        synchronized(imuDataRingBuffer) {
            imuDataRingBuffer.clear()
        }
    }

    private fun appendRecentWindow(window: FeatureWindow) {
        _recentFeatureWindows.value = (_recentFeatureWindows.value + window).takeLast(MAX_LIVE_WINDOWS)
    }

    private fun buildDetectionConfig(): DetectionConfig {
        return DetectionConfig(
            hrHighThreshold = preferencesManager.getHrHighThreshold(),
            hrLowThreshold = preferencesManager.getHrLowThreshold(),
            stepFreqHighThreshold = preferencesManager.getStepFreqHighThreshold(),
            stepFreqLowThreshold = preferencesManager.getStepFreqLowThreshold(),
            fallDetectionEnabled = preferencesManager.isFallDetectionEnabled()
        )
    }

    private fun buildJsonReport(report: BatchEvaluationReport): String {
        val root = JSONObject().apply {
            put("dataset_name", report.datasetName)
            put("total_samples", report.totalSamples)
            put("completed_samples", report.completedSamples)
            put("accuracy", report.accuracy)
            put("cancelled", report.cancelled)
            put("started_at", report.startedAt)
            put("finished_at", report.finishedAt)
            put("predicted_none_count", report.predictedNoneCount)
            put(
                "class_metrics",
                JSONArray().apply {
                    report.classMetrics.forEach { metric ->
                        put(
                            JSONObject().apply {
                                put("label", metric.label)
                                put("precision", metric.precision)
                                put("recall", metric.recall)
                                put("support", metric.support)
                            }
                        )
                    }
                }
            )
            put(
                "confusion_rows",
                JSONArray().apply {
                    report.confusionRows.forEach { row ->
                        put(
                            JSONObject().apply {
                                put("expected_label", row.expectedLabel)
                                put(
                                    "predicted_counts",
                                    JSONObject().apply {
                                        row.predictedCounts.forEach { (label, count) ->
                                            put(label, count)
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
            put(
                "sample_results",
                JSONArray().apply {
                    report.sampleResults.forEach { result ->
                        put(
                            JSONObject().apply {
                                put("sample_id", result.sampleId)
                                put("display_name", result.displayName)
                                put("expected_label", result.expectedLabel)
                                put("predicted_label", result.predictedLabel)
                                put("matched", result.matched)
                                put("top_severity", result.topSeverity)
                                put(
                                    "detected_types",
                                    JSONArray().apply {
                                        result.detectedTypes.forEach(::put)
                                    }
                                )
                                put("elapsed_ms", result.elapsedMs)
                                put("error", result.error)
                            }
                        )
                    }
                }
            )
        }
        return root.toString(2)
    }

    private fun buildCsvReport(report: BatchEvaluationReport): String {
        return buildString {
            appendLine("# dataset_name,${report.datasetName}")
            appendLine("# total_samples,${report.totalSamples}")
            appendLine("# completed_samples,${report.completedSamples}")
            appendLine("# accuracy,${report.accuracy}")
            appendLine("# cancelled,${report.cancelled}")
            appendLine("# started_at,${report.startedAt}")
            appendLine("# finished_at,${report.finishedAt}")
            appendLine("# predicted_none_count,${report.predictedNoneCount}")
            appendLine()
            appendLine("sample_id,display_name,expected_label,predicted_label,matched,top_severity,detected_types,elapsed_ms,error")
            report.sampleResults.forEach { result ->
                appendLine(
                    listOf(
                        result.sampleId,
                        csvEscape(result.displayName),
                        result.expectedLabel,
                        result.predictedLabel,
                        result.matched.toString(),
                        result.topSeverity.toString(),
                        csvEscape(result.detectedTypes.joinToString("|")),
                        result.elapsedMs.toString(),
                        csvEscape(result.error ?: "")
                    ).joinToString(",")
                )
            }
        }
    }

    private fun csvEscape(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun saveToDownloads(
        filename: String,
        mimeType: String,
        content: String
    ): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_FOLDER)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            )

            uri?.let { outputUri ->
                context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                outputUri.toString()
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val exportDir = File(downloadsDir, EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            val file = File(exportDir, filename)
            FileOutputStream(file).use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            file.absolutePath
        }
    }

    private sealed interface SamplePlaybackStatus {
        data object Completed : SamplePlaybackStatus
        data object Cancelled : SamplePlaybackStatus
        data class Failed(val message: String) : SamplePlaybackStatus
    }
}
