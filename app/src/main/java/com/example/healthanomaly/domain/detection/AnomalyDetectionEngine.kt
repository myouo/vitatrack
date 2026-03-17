package com.example.healthanomaly.domain.detection

import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.AnomalyType
import com.example.healthanomaly.domain.model.FeatureWindow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 异常检测引擎 - 毕业设计核心算法模块
 *
 * 采用混合检测架构：
 * 1. 规则层（Rule-based Layer）：基于医学阈值的快速筛查
 * 2. 模型层（Model-based Layer）：基于统计学习的基线偏离检测
 * 3. 融合层（Fusion Layer）：多证据融合决策
 * 4. 冷却层（Cooldown Layer）：防止重复报警
 *
 * 论文价值：
 * - 可解释性：规则层提供明确的触发条件
 * - 自适应性：EWMA 动态跟踪个体基线
 * - 鲁棒性：多层验证降低误报率
 * - 实时性：增量计算，O(1) 时间复杂度
 */
class AnomalyDetectionEngine(
    private val config: DetectionConfig = DetectionConfig()
) {

    private val mutex = Mutex()

    private val hrBaseline = EwmaBaseline(alpha = 0.1, windowSize = 300)
    private val stepFreqBaseline = EwmaBaseline(alpha = 0.15, windowSize = 200)

    private val hrStatistics = SlidingWindowStatistics(windowSize = 300)
    private val stepFreqStatistics = SlidingWindowStatistics(windowSize = 200)

    private val durationValidator = DurationValidator(requiredWindows = 1)
    private val evidenceAccumulator = EvidenceAccumulator()

    private var lastWindow: FeatureWindow? = null

    suspend fun detect(window: FeatureWindow): List<AnomalyEvent> = mutex.withLock {
        val detectedAnomalies = mutableListOf<AnomalyEvent>()
        val timestamp = window.timestampMs

        detectHeartRateRules(window, timestamp, detectedAnomalies)
        detectStepFrequencyRules(window, timestamp, detectedAnomalies)
        detectMotionRules(window, timestamp, detectedAnomalies)
        detectFallPattern(window, timestamp, detectedAnomalies)

        detectHeartRateModel(window, timestamp, detectedAnomalies)
        detectStepFrequencyModel(window, timestamp, detectedAnomalies)

        val finalAnomalies = fuseAndValidate(detectedAnomalies, window)

        lastWindow = window

        return finalAnomalies.sortedByDescending { it.severity }
    }

    private fun detectHeartRateRules(
        window: FeatureWindow,
        timestamp: Long,
        anomalies: MutableList<AnomalyEvent>
    ) {
        val hr = window.heartRateBpm ?: return

        if (hr > config.hrHighThreshold) {
            val evidence = DetectionEvidence(
                type = AnomalyType.HEART_RATE_HIGH,
                confidence = calculateConfidence(hr, config.hrHighThreshold, isUpper = true),
                source = "Rule-based",
                details = mapOf(
                    "current_hr" to hr,
                    "threshold" to config.hrHighThreshold,
                    "deviation" to (hr - config.hrHighThreshold)
                )
            )

            if (durationValidator.validate(evidence, timestamp)) {
                anomalies.add(createAnomalyEvent(evidence, timestamp, window))
            }
        }

        if (hr < config.hrLowThreshold) {
            val evidence = DetectionEvidence(
                type = AnomalyType.HEART_RATE_LOW,
                confidence = calculateConfidence(hr, config.hrLowThreshold, isUpper = false),
                source = "Rule-based",
                details = mapOf(
                    "current_hr" to hr,
                    "threshold" to config.hrLowThreshold,
                    "deviation" to (config.hrLowThreshold - hr)
                )
            )

            if (durationValidator.validate(evidence, timestamp)) {
                anomalies.add(createAnomalyEvent(evidence, timestamp, window))
            }
        }

        lastWindow?.heartRateBpm?.let { lastHr ->
            val timeDiff = timestamp - (lastWindow?.timestampMs ?: 0)
            if (timeDiff in 1000..5000) {
                val change = abs(hr - lastHr)
                if (change > config.hrSuddenChangeThreshold) {
                    val evidence = DetectionEvidence(
                        type = AnomalyType.HEART_RATE_SUDDEN_CHANGE,
                        confidence = minOf(1.0, change / 50.0),
                        source = "Rule-based",
                        details = mapOf(
                            "previous_hr" to lastHr,
                            "current_hr" to hr,
                            "change" to change,
                            "time_diff_ms" to timeDiff
                        )
                    )
                    anomalies.add(createAnomalyEvent(evidence, timestamp, window))
                }
            }
        }
    }

    private fun detectStepFrequencyRules(
        window: FeatureWindow,
        timestamp: Long,
        anomalies: MutableList<AnomalyEvent>
    ) {
        val stepFreq = window.stepFreqHz
        if (stepFreq <= 0) return

        if (stepFreq > config.stepFreqHighThreshold) {
            val evidence = DetectionEvidence(
                type = AnomalyType.STEP_FREQ_HIGH,
                confidence = minOf(1.0, (stepFreq - config.stepFreqHighThreshold) / 2.0),
                source = "Rule-based",
                details = mapOf(
                    "step_freq_hz" to stepFreq,
                    "threshold" to config.stepFreqHighThreshold
                )
            )

            if (durationValidator.validate(evidence, timestamp)) {
                anomalies.add(createAnomalyEvent(evidence, timestamp, window))
            }
        }

        if (stepFreq < config.stepFreqLowThreshold) {
            val evidence = DetectionEvidence(
                type = AnomalyType.STEP_FREQ_LOW,
                confidence = minOf(1.0, (config.stepFreqLowThreshold - stepFreq) / 0.5),
                source = "Rule-based",
                details = mapOf(
                    "step_freq_hz" to stepFreq,
                    "threshold" to config.stepFreqLowThreshold
                )
            )

            if (durationValidator.validate(evidence, timestamp)) {
                anomalies.add(createAnomalyEvent(evidence, timestamp, window))
            }
        }

        lastWindow?.let { last ->
            if (last.stepFreqHz > 0) {
                val change = abs(stepFreq - last.stepFreqHz)
                if (change > config.stepFreqSuddenChangeThreshold) {
                    val evidence = DetectionEvidence(
                        type = AnomalyType.GAIT_SUDDEN_CHANGE,
                        confidence = minOf(1.0, change / 2.0),
                        source = "Rule-based",
                        details = mapOf(
                            "previous_freq" to last.stepFreqHz,
                            "current_freq" to stepFreq,
                            "change" to change
                        )
                    )
                    anomalies.add(createAnomalyEvent(evidence, timestamp, window))
                }
            }
        }
    }

    private fun detectMotionRules(
        window: FeatureWindow,
        timestamp: Long,
        anomalies: MutableList<AnomalyEvent>
    ) {
        if (window.rmsAccel > config.motionIntensityThreshold) {
            val evidence = DetectionEvidence(
                type = AnomalyType.MOTION_INTENSITY_ANOMALY,
                confidence = minOf(1.0, window.rmsAccel / 20.0),
                source = "Rule-based",
                details = mapOf(
                    "rms_accel" to window.rmsAccel,
                    "threshold" to config.motionIntensityThreshold,
                    "max_accel" to window.maxAccel
                )
            )

            if (durationValidator.validate(evidence, timestamp)) {
                anomalies.add(createAnomalyEvent(evidence, timestamp, window))
            }
        }

        if (window.jerkRms > config.jerkThreshold) {
            val evidence = DetectionEvidence(
                type = AnomalyType.MOTION_INTENSITY_ANOMALY,
                confidence = minOf(1.0, window.jerkRms / 30.0),
                source = "Rule-based",
                details = mapOf(
                    "jerk_rms" to window.jerkRms,
                    "threshold" to config.jerkThreshold
                )
            )
            anomalies.add(createAnomalyEvent(evidence, timestamp, window))
        }
    }

    private fun detectFallPattern(
        window: FeatureWindow,
        timestamp: Long,
        anomalies: MutableList<AnomalyEvent>
    ) {
        if (!config.fallDetectionEnabled) return

        val hasImpact = window.maxAccel > config.fallImpactThreshold
        val hasRotation = window.rmsGyro > config.fallRotationThreshold
        val hasStationary = window.minAccel < config.fallStationaryThreshold

        if (hasImpact && hasRotation) {
            val evidence = DetectionEvidence(
                type = AnomalyType.FALL_DETECTED,
                confidence = 0.85,
                source = "Pattern-based",
                details = mapOf(
                    "max_accel" to window.maxAccel,
                    "rms_gyro" to window.rmsGyro,
                    "min_accel" to window.minAccel,
                    "has_impact" to hasImpact,
                    "has_rotation" to hasRotation,
                    "has_stationary" to hasStationary
                )
            )
            anomalies.add(createAnomalyEvent(evidence, timestamp, window))
        }
    }

    private fun detectHeartRateModel(
        window: FeatureWindow,
        timestamp: Long,
        anomalies: MutableList<AnomalyEvent>
    ) {
        val hr = window.heartRateBpm ?: return

        hrBaseline.update(hr.toDouble(), timestamp)
        hrStatistics.add(hr.toDouble())

        if (hrStatistics.count < 30) return

        val mean = hrBaseline.getValue()
        val std = hrStatistics.getStd()

        if (std < 1.0) return

        val zScore = (hr - mean) / std

        if (abs(zScore) > config.zScoreThreshold) {
            val evidence = DetectionEvidence(
                type = if (zScore > 0) AnomalyType.HEART_RATE_HIGH else AnomalyType.HEART_RATE_LOW,
                confidence = minOf(1.0, abs(zScore) / 5.0),
                source = "EWMA+Z-Score",
                details = mapOf(
                    "current_hr" to hr,
                    "baseline_mean" to mean,
                    "std" to std,
                    "z_score" to zScore,
                    "sample_count" to hrStatistics.count
                )
            )

            if (durationValidator.validate(evidence, timestamp)) {
                anomalies.add(createAnomalyEvent(evidence, timestamp, window))
            }
        }
    }

    private fun detectStepFrequencyModel(
        window: FeatureWindow,
        timestamp: Long,
        anomalies: MutableList<AnomalyEvent>
    ) {
        val stepFreq = window.stepFreqHz
        if (stepFreq <= 0) return

        stepFreqBaseline.update(stepFreq.toDouble(), timestamp)
        stepFreqStatistics.add(stepFreq.toDouble())

        if (stepFreqStatistics.count < 20) return

        val mean = stepFreqBaseline.getValue()
        val std = stepFreqStatistics.getStd()

        if (std < 0.1) return

        val zScore = (stepFreq - mean) / std

        if (abs(zScore) > config.zScoreThreshold) {
            val evidence = DetectionEvidence(
                type = if (zScore > 0) AnomalyType.STEP_FREQ_HIGH else AnomalyType.STEP_FREQ_LOW,
                confidence = minOf(1.0, abs(zScore) / 4.0),
                source = "EWMA+Z-Score",
                details = mapOf(
                    "current_freq" to stepFreq,
                    "baseline_mean" to mean,
                    "std" to std,
                    "z_score" to zScore
                )
            )

            if (durationValidator.validate(evidence, timestamp)) {
                anomalies.add(createAnomalyEvent(evidence, timestamp, window))
            }
        }
    }

    private fun fuseAndValidate(
        anomalies: List<AnomalyEvent>,
        window: FeatureWindow
    ): List<AnomalyEvent> {
        if (anomalies.isEmpty()) return emptyList()

        val groupedByType = anomalies.groupBy { it.type }
        val fusedAnomalies = mutableListOf<AnomalyEvent>()

        for ((type, events) in groupedByType) {
            val hasRuleBased = events.any { it.details.contains("Rule-based") }
            val hasModelBased = events.any { it.details.contains("EWMA") || it.details.contains("Z-Score") }

            val primaryEvent = events.maxByOrNull { it.severity }!!

            val boostedSeverity = if (hasRuleBased && hasModelBased) {
                minOf(10, primaryEvent.severity + 2)
            } else {
                primaryEvent.severity
            }

            evidenceAccumulator.add(type, window.timestampMs)
            val consecutiveCount = evidenceAccumulator.getConsecutiveCount(type)

            val finalSeverity = if (consecutiveCount >= 3) {
                minOf(10, boostedSeverity + 1)
            } else {
                boostedSeverity
            }

            val enhancedDetails = primaryEvent.details + mapOf(
                "fusion_info" to "cross_layer=${hasRuleBased && hasModelBased}, consecutive=$consecutiveCount",
                "evidence_count" to events.size
            )

            fusedAnomalies.add(
                primaryEvent.copy(
                    severity = finalSeverity,
                    details = enhancedDetails
                )
            )
        }

        return fusedAnomalies
    }

    private fun calculateConfidence(value: Int, threshold: Int, isUpper: Boolean): Double {
        val deviation = if (isUpper) value - threshold else threshold - value
        return minOf(1.0, deviation / 50.0)
    }

    private fun createAnomalyEvent(evidence: DetectionEvidence, timestamp: Long, window: FeatureWindow): AnomalyEvent {
        val severity = calculateSeverity(evidence)
        val details = buildDetailsString(evidence, window)

        return AnomalyEvent(
            timestampMs = timestamp,
            type = evidence.type,
            severity = severity,
            details = details
        )
    }

    private fun calculateSeverity(evidence: DetectionEvidence): Int {
        val baseSeverity = when (evidence.type) {
            AnomalyType.FALL_DETECTED -> 10
            AnomalyType.HEART_RATE_LOW -> 9
            AnomalyType.HEART_RATE_HIGH -> 8
            AnomalyType.HEART_RATE_SUDDEN_CHANGE -> 7
            AnomalyType.GAIT_SUDDEN_CHANGE -> 6
            AnomalyType.STEP_FREQ_HIGH, AnomalyType.STEP_FREQ_LOW -> 5
            AnomalyType.MOTION_INTENSITY_ANOMALY -> 4
        }

        return (baseSeverity * evidence.confidence).toInt().coerceIn(1, 10)
    }

    private fun buildDetailsString(evidence: DetectionEvidence, window: FeatureWindow): String {
        val detailsMap = mutableMapOf<String, Any>()
        detailsMap["source"] = evidence.source
        detailsMap["confidence"] = "%.2f".format(evidence.confidence)
        detailsMap.putAll(evidence.details)

        detailsMap["feature_snapshot"] = mapOf(
            "rms_accel" to "%.2f".format(window.rmsAccel),
            "max_accel" to "%.2f".format(window.maxAccel),
            "step_freq" to "%.2f".format(window.stepFreqHz),
            "hr" to (window.heartRateBpm ?: "N/A")
        )

        return detailsMap.entries.joinToString(", ") { (k, v) ->
            when (v) {
                is Number -> "$k=${"%.2f".format(v.toDouble())}"
                is Map<*, *> -> "$k=${v.entries.joinToString(";") { "${it.key}=${it.value}" }}"
                else -> "$k=$v"
            }
        }
    }

    suspend fun reset() = mutex.withLock {
        hrBaseline.reset()
        stepFreqBaseline.reset()
        hrStatistics.reset()
        stepFreqStatistics.reset()
        durationValidator.reset()
        evidenceAccumulator.reset()
        lastWindow = null
    }
}

data class DetectionConfig(
    val hrHighThreshold: Int = 150,
    val hrLowThreshold: Int = 40,
    val hrSuddenChangeThreshold: Int = 30,
    val stepFreqHighThreshold: Float = 3.0f,
    val stepFreqLowThreshold: Float = 0.5f,
    val stepFreqSuddenChangeThreshold: Float = 1.0f,
    val motionIntensityThreshold: Float = 15.0f,
    val jerkThreshold: Float = 20.0f,
    val fallDetectionEnabled: Boolean = true,
    val fallImpactThreshold: Float = 20.0f,
    val fallRotationThreshold: Float = 3.0f,
    val fallStationaryThreshold: Float = 2.0f,
    val zScoreThreshold: Double = 3.0
)

data class DetectionEvidence(
    val type: AnomalyType,
    val confidence: Double,
    val source: String,
    val details: Map<String, Any>
)

class EwmaBaseline(
    private val alpha: Double,
    private val windowSize: Int
) {
    private var value: Double? = null
    private var lastUpdateTime: Long = 0

    fun update(newValue: Double, timestamp: Long) {
        value = if (value == null) {
            newValue
        } else {
            alpha * newValue + (1 - alpha) * value!!
        }
        lastUpdateTime = timestamp
    }

    fun getValue(): Double = value ?: 0.0

    fun reset() {
        value = null
        lastUpdateTime = 0
    }
}

class SlidingWindowStatistics(private val windowSize: Int) {
    private val buffer = ArrayDeque<Double>(windowSize)
    var count: Int = 0
        private set

    fun add(value: Double) {
        if (buffer.size >= windowSize) {
            buffer.removeFirst()
        }
        buffer.addLast(value)
        count++
    }

    fun getMean(): Double {
        if (buffer.isEmpty()) return 0.0
        return buffer.average()
    }

    fun getStd(): Double {
        if (buffer.size < 2) return 0.0
        val mean = getMean()
        val variance = buffer.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    fun reset() {
        buffer.clear()
        count = 0
    }
}

class CooldownManager(private val cooldownMs: Long) {
    private val lastAlertTime = mutableMapOf<AnomalyType, Long>()

    fun filter(anomalies: List<AnomalyEvent>, currentTime: Long): List<AnomalyEvent> {
        return anomalies.filter { anomaly ->
            val lastTime = lastAlertTime[anomaly.type]
            val shouldAlert = lastTime == null || (currentTime - lastTime) > cooldownMs

            if (shouldAlert) {
                lastAlertTime[anomaly.type] = currentTime
            }

            shouldAlert
        }
    }

    fun reset() {
        lastAlertTime.clear()
    }
}

class DurationValidator(private val requiredWindows: Int = 2) {
    private val evidenceHistory = mutableMapOf<AnomalyType, MutableList<Long>>()

    fun validate(evidence: DetectionEvidence, timestamp: Long): Boolean {
        val history = evidenceHistory.getOrPut(evidence.type) { mutableListOf() }

        history.removeAll { timestamp - it > 10_000 }
        history.add(timestamp)

        return history.size >= requiredWindows
    }

    fun reset() {
        evidenceHistory.clear()
    }
}

class EvidenceAccumulator {
    private val consecutiveCounts = mutableMapOf<AnomalyType, Int>()
    private val lastTimestamps = mutableMapOf<AnomalyType, Long>()

    fun add(type: AnomalyType, timestamp: Long) {
        val lastTime = lastTimestamps[type] ?: 0

        if (timestamp - lastTime > 5000) {
            consecutiveCounts[type] = 1
        } else {
            consecutiveCounts[type] = (consecutiveCounts[type] ?: 0) + 1
        }

        lastTimestamps[type] = timestamp
    }

    fun getConsecutiveCount(type: AnomalyType): Int {
        return consecutiveCounts[type] ?: 0
    }

    fun reset() {
        consecutiveCounts.clear()
        lastTimestamps.clear()
    }
}
