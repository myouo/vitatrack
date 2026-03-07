package com.example.healthanomaly.data.stream

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.healthanomaly.R
import com.example.healthanomaly.domain.model.HeartRateData
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.repository.BleDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class FilePlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val STREAM_DEVICE_ADDRESS = "FILE_STREAM_SAMPLE"
        private const val PLAYBACK_SPEED = 4.0
        private const val MAX_BUFFER_SIZE = 2_000
        private const val RING_BUFFER_DURATION_MS = 30_000L
        private val STREAM_RESOURCE_CANDIDATES = listOf(
            "sample_stream_jsonl",
            "sample_stream"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _imuDataFlow = MutableSharedFlow<ImuData>(
        replay = 0,
        extraBufferCapacity = 128
    )
    private val _heartRateFlow = MutableStateFlow<HeartRateData?>(null)
    private val _connectionStateFlow = MutableStateFlow(BleConnectionState.DISCONNECTED)
    private val _scanResultsFlow = MutableStateFlow<List<BleDevice>>(emptyList())
    private val _playbackCompletedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _playbackErrorFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val ringBuffer = ArrayDeque<ImuData>(MAX_BUFFER_SIZE)
    private val bundledStream: StreamContent? by lazy { loadBundledStream() }

    private var playbackJob: Job? = null
    private var latestTimestampMs: Long = 0L
    private var currentStream: StreamContent? = null

    val imuDataFlow: Flow<ImuData> = _imuDataFlow.asSharedFlow()
    val heartRateFlow: Flow<HeartRateData> = _heartRateFlow.filterNotNull()
    val connectionStateFlow: StateFlow<BleConnectionState> = _connectionStateFlow.asStateFlow()
    val scanResultsFlow: StateFlow<List<BleDevice>> = _scanResultsFlow.asStateFlow()
    val playbackCompletedFlow: Flow<Unit> = _playbackCompletedFlow.asSharedFlow()
    val playbackErrorFlow: Flow<String> = _playbackErrorFlow.asSharedFlow()

    suspend fun loadStream(uri: Uri, displayName: String? = null): LoadedStreamInfo =
        withContext(Dispatchers.IO) {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: throw IllegalArgumentException("Unable to read selected file")

            val resolvedName = displayName ?: resolveDisplayName(uri)
                ?: context.getString(R.string.stream_default_name)
            val rows = parseContent(uri.toString(), content)
            if (rows.isEmpty()) {
                throw IllegalArgumentException("No valid stream samples found")
            }

            currentStream = StreamContent(
                samples = rows,
                displayName = resolvedName
            )
            publishVirtualSource(resolvedName)

            LoadedStreamInfo(
                displayName = resolvedName,
                sampleCount = rows.size
            )
        }

    fun startPlayback() {
        val stream = currentStream ?: bundledStream ?: return
        if (playbackJob?.isActive == true || stream.samples.isEmpty()) {
            return
        }

        publishVirtualSource(stream.displayName)
        _connectionStateFlow.value = BleConnectionState.CONNECTED

        playbackJob = scope.launch {
            val result = runCatching {
                replayStream(stream.samples)
            }

            playbackJob = null

            result.onSuccess {
                completePlayback()
                _playbackCompletedFlow.tryEmit(Unit)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    return@launch
                }
                completePlayback()
                _playbackErrorFlow.tryEmit(
                    throwable.message ?: context.getString(R.string.playback_failed)
                )
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        latestTimestampMs = 0L
        synchronized(ringBuffer) {
            ringBuffer.clear()
        }
        _connectionStateFlow.value = BleConnectionState.DISCONNECTED
        _heartRateFlow.value = null
        _scanResultsFlow.value = emptyList()
    }

    fun publishVirtualSource(
        displayName: String = currentStream?.displayName
            ?: bundledStream?.displayName
            ?: context.getString(R.string.stream_default_name)
    ) {
        _scanResultsFlow.value = listOf(
            BleDevice(
                address = STREAM_DEVICE_ADDRESS,
                name = displayName,
                rssi = 0
            )
        )
    }

    suspend fun getRecentSamples(durationMs: Long): List<ImuData> {
        val cutoff = latestTimestampMs - durationMs
        return synchronized(ringBuffer) {
            ringBuffer.filter { it.timestampMs >= cutoff }
        }
    }

    fun isPlaying(): Boolean = playbackJob?.isActive == true

    private fun loadBundledStream(): StreamContent? {
        val resource = resolveStreamResource() ?: return null
        val content = context.resources.openRawResource(resource.resourceId)
            .bufferedReader()
            .use { it.readText() }
        val rows = parseContent(resource.name, content)
        if (rows.isEmpty()) {
            return null
        }

        return StreamContent(
            samples = rows,
            displayName = context.getString(R.string.stream_default_name)
        )
    }

    private fun parseContent(sourceName: String, content: String): List<FileSampleRow> {
        val parsedRows = when (detectFormat(sourceName, content)) {
            StreamFormat.JSONL -> parseJsonl(content)
            StreamFormat.CSV -> parseCsv(content)
            StreamFormat.UNKNOWN -> emptyList()
        }
        return normalizeTimestamps(parsedRows.filter(::isValidSample))
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return context.contentResolver.query(
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

    private suspend fun replayStream(rows: List<FileSampleRow>) {
        rows.forEachIndexed { index, row ->
            val adjustedTimestamp = row.timestampMs
            val previousTimestamp = when {
                index == 0 -> 0L
                else -> rows[index - 1].timestampMs
            }
            val sampleDelayMs = if (index == 0 && adjustedTimestamp == 0L) {
                0L
            } else {
                (((adjustedTimestamp - previousTimestamp) / PLAYBACK_SPEED).toLong())
                    .coerceAtLeast(0L)
            }

            if (sampleDelayMs > 0) {
                delay(sampleDelayMs)
            }

            emitSample(row, adjustedTimestamp)
        }
    }

    private suspend fun emitSample(row: FileSampleRow, adjustedTimestampMs: Long) {
        val imuData = ImuData(
            timestampMs = adjustedTimestampMs,
            accelX = row.accelX,
            accelY = row.accelY,
            accelZ = row.accelZ,
            gyroX = row.gyroX,
            gyroY = row.gyroY,
            gyroZ = row.gyroZ
        )

        synchronized(ringBuffer) {
            if (ringBuffer.size >= MAX_BUFFER_SIZE) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(imuData)

            val cutoff = adjustedTimestampMs - RING_BUFFER_DURATION_MS
            while (ringBuffer.isNotEmpty() && ringBuffer.first().timestampMs < cutoff) {
                ringBuffer.removeFirst()
            }
        }

        latestTimestampMs = adjustedTimestampMs
        _imuDataFlow.emit(imuData)
        row.heartRateBpm?.let { heartRate ->
            _heartRateFlow.value = HeartRateData(
                timestampMs = adjustedTimestampMs,
                heartRateBpm = heartRate,
                deviceAddress = STREAM_DEVICE_ADDRESS
            )
        }
    }

    private fun resolveStreamResource(): StreamResource? {
        return STREAM_RESOURCE_CANDIDATES.firstNotNullOfOrNull { name ->
            runCatching {
                val resourceId = R.raw::class.java.getField(name).getInt(null)
                StreamResource(resourceId = resourceId, name = name)
            }.getOrNull()
        }
    }

    private fun detectFormat(resourceName: String, content: String): StreamFormat {
        val firstMeaningfulLine = content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return StreamFormat.UNKNOWN

        return when {
            resourceName.contains("jsonl", ignoreCase = true) -> StreamFormat.JSONL
            firstMeaningfulLine.startsWith("{") -> StreamFormat.JSONL
            resourceName.contains("csv", ignoreCase = true) -> StreamFormat.CSV
            firstMeaningfulLine.contains(",") -> StreamFormat.CSV
            else -> StreamFormat.UNKNOWN
        }
    }

    private fun parseJsonl(content: String): List<FileSampleRow> {
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                runCatching { JSONObject(line) }.getOrNull()?.let { root ->
                    parseJsonObject(root)
                }
            }
            .toList()
    }

    private fun parseJsonObject(root: JSONObject): FileSampleRow? {
        val containers = buildList {
            add(root)
            listOf("data", "payload", "values", "validData").forEach { key ->
                root.optJSONObject(key)?.let(::add)
            }
        }

        val timestampMs = findLong(containers, "timestampMs", "timestamp", "time", "ts")
            ?: return null
        val accelVector = findVector(containers, "accel", "accelerometer", "acceleration")
        val gyroVector = findVector(containers, "gyro", "gyroscope", "rotation")

        val accelX = findFloat(containers, "accelX", "accel_x", "ax", "accelerationX", "acceleration_x")
            ?: accelVector?.x
            ?: return null
        val accelY = findFloat(containers, "accelY", "accel_y", "ay", "accelerationY", "acceleration_y")
            ?: accelVector?.y
            ?: return null
        val accelZ = findFloat(containers, "accelZ", "accel_z", "az", "accelerationZ", "acceleration_z")
            ?: accelVector?.z
            ?: return null

        val gyroX = findFloat(containers, "gyroX", "gyro_x", "gx", "rotationX", "rotation_x")
            ?: gyroVector?.x
            ?: 0f
        val gyroY = findFloat(containers, "gyroY", "gyro_y", "gy", "rotationY", "rotation_y")
            ?: gyroVector?.y
            ?: 0f
        val gyroZ = findFloat(containers, "gyroZ", "gyro_z", "gz", "rotationZ", "rotation_z")
            ?: gyroVector?.z
            ?: 0f
        val heartRateBpm = findInt(
            containers,
            "heartRateBpm",
            "heartRate",
            "heart_rate_bpm",
            "heart_rate",
            "hr",
            "bpm"
        )

        return FileSampleRow(
            timestampMs = timestampMs,
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            heartRateBpm = heartRateBpm
        )
    }

    private fun parseCsv(content: String): List<FileSampleRow> {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) {
            return emptyList()
        }

        val headers = lines.first().split(',').map(::normalizeKey)
        return lines.drop(1).mapNotNull { line ->
            val values = line.split(',').map { it.trim() }
            if (values.size != headers.size) {
                return@mapNotNull null
            }

            val rowMap = headers.zip(values).toMap()
            val timestampMs = findLong(rowMap, "timestampms", "timestamp", "time", "ts")
                ?: return@mapNotNull null

            val accelX = findFloat(rowMap, "accelx", "ax", "accelerationx")
                ?: return@mapNotNull null
            val accelY = findFloat(rowMap, "accely", "ay", "accelerationy")
                ?: return@mapNotNull null
            val accelZ = findFloat(rowMap, "accelz", "az", "accelerationz")
                ?: return@mapNotNull null

            FileSampleRow(
                timestampMs = timestampMs,
                accelX = accelX,
                accelY = accelY,
                accelZ = accelZ,
                gyroX = findFloat(rowMap, "gyrox", "gx", "rotationx") ?: 0f,
                gyroY = findFloat(rowMap, "gyroy", "gy", "rotationy") ?: 0f,
                gyroZ = findFloat(rowMap, "gyroz", "gz", "rotationz") ?: 0f,
                heartRateBpm = findInt(rowMap, "heartratebpm", "heartrate", "hr", "bpm")
            )
        }
    }

    private fun normalizeTimestamps(rows: List<FileSampleRow>): List<FileSampleRow> {
        if (rows.isEmpty()) {
            return emptyList()
        }

        val sortedRows = rows.sortedBy { it.timestampMs }
        val originTimestamp = sortedRows.first().timestampMs
        return sortedRows.map { row ->
            row.copy(timestampMs = row.timestampMs - originTimestamp)
        }
    }

    private fun isValidSample(row: FileSampleRow): Boolean {
        if (row.timestampMs < 0L) {
            return false
        }

        if (!row.accelX.isFinite() || !row.accelY.isFinite() || !row.accelZ.isFinite()) {
            return false
        }

        if (!row.gyroX.isFinite() || !row.gyroY.isFinite() || !row.gyroZ.isFinite()) {
            return false
        }

        val heartRate = row.heartRateBpm
        return heartRate == null || heartRate in 25..240
    }

    private fun findVector(containers: List<JSONObject>, vararg keys: String): Vector3? {
        containers.forEach { container ->
            keys.forEach { key ->
                container.optJSONObject(key)?.let { vectorObject ->
                    val x = vectorObject.optDouble("x", Double.NaN)
                    val y = vectorObject.optDouble("y", Double.NaN)
                    val z = vectorObject.optDouble("z", Double.NaN)
                    if (!x.isNaN() && !y.isNaN() && !z.isNaN()) {
                        return Vector3(x.toFloat(), y.toFloat(), z.toFloat())
                    }
                }

                container.optJSONArray(key)?.let { vectorArray ->
                    parseVectorArray(vectorArray)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseVectorArray(vectorArray: JSONArray): Vector3? {
        if (vectorArray.length() < 3) {
            return null
        }

        val x = vectorArray.optDouble(0, Double.NaN)
        val y = vectorArray.optDouble(1, Double.NaN)
        val z = vectorArray.optDouble(2, Double.NaN)
        if (x.isNaN() || y.isNaN() || z.isNaN()) {
            return null
        }

        return Vector3(x.toFloat(), y.toFloat(), z.toFloat())
    }

    private fun findLong(containers: List<JSONObject>, vararg keys: String): Long? {
        containers.forEach { container ->
            keys.forEach { key ->
                if (container.has(key) && !container.isNull(key)) {
                    return container.optString(key).toLongOrNull()
                        ?: container.optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.toLong()
                }
            }
        }
        return null
    }

    private fun findInt(containers: List<JSONObject>, vararg keys: String): Int? {
        containers.forEach { container ->
            keys.forEach { key ->
                if (container.has(key) && !container.isNull(key)) {
                    return container.optString(key).toIntOrNull()
                        ?: container.optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.toInt()
                }
            }
        }
        return null
    }

    private fun findFloat(containers: List<JSONObject>, vararg keys: String): Float? {
        containers.forEach { container ->
            keys.forEach { key ->
                if (container.has(key) && !container.isNull(key)) {
                    return container.optString(key).toFloatOrNull()
                        ?: container.optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                }
            }
        }
        return null
    }

    private fun findLong(rowMap: Map<String, String>, vararg keys: String): Long? {
        return keys.firstNotNullOfOrNull { key -> rowMap[key]?.toLongOrNull() }
    }

    private fun findInt(rowMap: Map<String, String>, vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key -> rowMap[key]?.toIntOrNull() }
    }

    private fun findFloat(rowMap: Map<String, String>, vararg keys: String): Float? {
        return keys.firstNotNullOfOrNull { key -> rowMap[key]?.toFloatOrNull() }
    }

    private fun normalizeKey(value: String): String {
        return value.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun completePlayback() {
        _connectionStateFlow.value = BleConnectionState.DISCONNECTED
    }
}

private data class FileSampleRow(
    val timestampMs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val heartRateBpm: Int?
)

private data class StreamResource(
    val resourceId: Int,
    val name: String
)

data class LoadedStreamInfo(
    val displayName: String,
    val sampleCount: Int
)

private data class StreamContent(
    val samples: List<FileSampleRow>,
    val displayName: String
)

private enum class StreamFormat {
    JSONL,
    CSV,
    UNKNOWN
}

private data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
)
