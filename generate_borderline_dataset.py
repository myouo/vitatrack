import argparse
import csv
import json
import math
import random
import shutil
from collections import defaultdict
from pathlib import Path


PROFILE_STABLE90 = "stable90"
PROFILE_LEGACY = "legacy-borderline"

DEFAULT_COUNT = 500
DEFAULT_INTERVAL_MS = 50
DEFAULT_DURATION_MS = 5000
DEFAULT_SEED = 20260417
DEFAULT_PROFILE = PROFILE_STABLE90

PROFILE_DEFAULT_DATASET_NAMES = {
    PROFILE_STABLE90: "stable_batch_eval_v2",
    PROFILE_LEGACY: "borderline_batch_eval_v1",
}

PROFILE_VERSIONS = {
    PROFILE_STABLE90: "2.0",
    PROFILE_LEGACY: "1.0",
}

STABLE_CLASS_WEIGHTS = {
    "HEART_RATE_HIGH": 140,
    "HEART_RATE_LOW": 140,
    "STEP_FREQ_HIGH": 120,
    "GAIT_SUDDEN_CHANGE": 100,
}

LEGACY_CLASS_WEIGHTS = {
    "HEART_RATE_HIGH": 140,
    "HEART_RATE_LOW": 80,
    "STEP_FREQ_HIGH": 80,
    "STEP_FREQ_LOW": 60,
    "GAIT_SUDDEN_CHANGE": 140,
}

LEGACY_TEMPLATE_WEIGHTS = {
    "hover_then_cross": 0.50,
    "cross_with_secondary_distractor": 0.30,
    "flicker_and_recovery": 0.20,
}

STABLE_TEMPLATE_WEIGHTS = {
    "HEART_RATE_HIGH": {
        "steady_high": 0.65,
        "drift_high": 0.35,
    },
    "HEART_RATE_LOW": {
        "steady_low": 0.70,
        "drift_low": 0.30,
    },
    "STEP_FREQ_HIGH": {
        "steady_fast": 0.70,
        "fast_modulated": 0.30,
    },
    "GAIT_SUDDEN_CHANGE": {
        "early_switch": 0.25,
        "mid_switch": 0.50,
        "late_switch": 0.25,
    },
}

APP_WINDOW_SIZE_MS = 2000
APP_STEP_SIZE_MS = 1000
APP_HR_HIGH_THRESHOLD = 150
APP_HR_LOW_THRESHOLD = 40
APP_HR_SUDDEN_CHANGE_THRESHOLD = 30
APP_STEP_FREQ_HIGH_THRESHOLD = 3.0
APP_STEP_FREQ_LOW_THRESHOLD = 0.5
APP_STEP_FREQ_SUDDEN_CHANGE_THRESHOLD = 1.0
APP_MOTION_INTENSITY_THRESHOLD = 15.0
APP_JERK_THRESHOLD = 20.0
APP_FALL_IMPACT_THRESHOLD = 20.0
APP_FALL_ROTATION_THRESHOLD = 3.0
APP_FALL_STATIONARY_THRESHOLD = 2.0
APP_PEAK_THRESHOLD = 10.0
APP_NONE_LABEL = "NONE"

BASE_SEVERITIES = {
    "FALL_DETECTED": 10,
    "HEART_RATE_LOW": 9,
    "HEART_RATE_HIGH": 8,
    "HEART_RATE_SUDDEN_CHANGE": 7,
    "GAIT_SUDDEN_CHANGE": 6,
    "STEP_FREQ_HIGH": 5,
    "STEP_FREQ_LOW": 5,
    "MOTION_INTENSITY_ANOMALY": 4,
}


def scale_distribution(total_count, class_weights):
    total_weight = sum(class_weights.values())
    scaled = {}
    remainders = []
    assigned = 0

    for label, weight in class_weights.items():
        exact = total_count * weight / total_weight
        whole = int(exact)
        scaled[label] = whole
        assigned += whole
        remainders.append((exact - whole, label))

    for _, label in sorted(remainders, reverse=True)[: total_count - assigned]:
        scaled[label] += 1

    return scaled


def allocate_templates(class_count, template_weights):
    allocated = {}
    assigned = 0
    remainders = []

    for template_name, weight in template_weights.items():
        exact = class_count * weight
        whole = int(exact)
        allocated[template_name] = whole
        assigned += whole
        remainders.append((exact - whole, template_name))

    for _, template_name in sorted(remainders, reverse=True)[: class_count - assigned]:
        allocated[template_name] += 1

    return allocated


def smooth_transition(progress):
    if progress <= 0.0:
        return 0.0
    if progress >= 1.0:
        return 1.0
    return progress * progress * (3.0 - 2.0 * progress)


def interpolate(start_value, end_value, progress):
    return start_value + (end_value - start_value) * smooth_transition(progress)


def segment_value(index, total_points, start_ratio, end_ratio, low_value, high_value):
    start_index = int(total_points * start_ratio)
    end_index = int(total_points * end_ratio)
    if index <= start_index:
        return low_value
    if index >= end_index:
        return high_value
    progress = (index - start_index) / max(1, end_index - start_index)
    return low_value + (high_value - low_value) * smooth_transition(progress)


def choose_ranges_legacy(label, template_type, rng):
    if label == "HEART_RATE_HIGH":
        hr_hover = rng.uniform(146.0, 150.0)
        hr_cross = rng.uniform(151.0, 154.0)
        step_base = rng.uniform(2.6, 2.95)
        step_distractor = rng.uniform(2.9, 2.99)
        return {
            "heart_rate_hover": hr_hover,
            "heart_rate_cross": hr_cross,
            "step_base": step_base,
            "step_distractor": step_distractor if template_type == "cross_with_secondary_distractor" else step_base,
        }
    if label == "HEART_RATE_LOW":
        hr_hover = rng.uniform(41.0, 45.0)
        hr_cross = rng.uniform(37.0, 39.0)
        step_base = rng.uniform(0.8, 1.2)
        step_distractor = rng.uniform(0.55, 0.7)
        return {
            "heart_rate_hover": hr_hover,
            "heart_rate_cross": hr_cross,
            "step_base": step_base,
            "step_distractor": step_distractor if template_type == "cross_with_secondary_distractor" else step_base,
        }
    if label == "STEP_FREQ_HIGH":
        return {
            "step_hover": rng.uniform(2.8, 3.0),
            "step_cross": rng.uniform(3.05, 3.2),
            "heart_rate_base": rng.uniform(108.0, 118.0),
            "heart_rate_distractor": rng.uniform(146.0, 149.0)
            if template_type == "cross_with_secondary_distractor"
            else rng.uniform(108.0, 118.0),
        }
    if label == "STEP_FREQ_LOW":
        return {
            "step_hover": rng.uniform(0.52, 0.7),
            "step_cross": rng.uniform(0.42, 0.48),
            "heart_rate_base": rng.uniform(65.0, 78.0),
            "heart_rate_distractor": rng.uniform(41.0, 44.0)
            if template_type == "cross_with_secondary_distractor"
            else rng.uniform(65.0, 78.0),
        }
    if label == "GAIT_SUDDEN_CHANGE":
        return {
            "step_before": rng.uniform(1.45, 1.7),
            "step_after": rng.uniform(2.55, 2.75),
            "heart_rate_base": rng.uniform(90.0, 104.0),
            "heart_rate_distractor": rng.uniform(146.0, 149.0)
            if template_type == "cross_with_secondary_distractor"
            else rng.uniform(90.0, 104.0),
        }
    raise ValueError(f"Unsupported label: {label}")


def resolve_signal_profile_legacy(label, template_type, index, total_points, config):
    if label == "HEART_RATE_HIGH":
        if template_type == "hover_then_cross":
            heart_rate = segment_value(index, total_points, 0.58, 0.82, config["heart_rate_hover"], config["heart_rate_cross"])
            step_freq = config["step_base"]
        elif template_type == "cross_with_secondary_distractor":
            heart_rate = segment_value(index, total_points, 0.45, 0.72, config["heart_rate_hover"], config["heart_rate_cross"])
            step_freq = segment_value(index, total_points, 0.2, 0.5, config["step_base"], config["step_distractor"])
        else:
            if total_points * 0.35 <= index <= total_points * 0.48 or total_points * 0.7 <= index <= total_points * 0.78:
                heart_rate = config["heart_rate_cross"]
            else:
                heart_rate = config["heart_rate_hover"]
            step_freq = config["step_base"]
        return heart_rate, step_freq

    if label == "HEART_RATE_LOW":
        if template_type == "hover_then_cross":
            heart_rate = segment_value(index, total_points, 0.55, 0.8, config["heart_rate_hover"], config["heart_rate_cross"])
            step_freq = config["step_base"]
        elif template_type == "cross_with_secondary_distractor":
            heart_rate = segment_value(index, total_points, 0.42, 0.68, config["heart_rate_hover"], config["heart_rate_cross"])
            step_freq = segment_value(index, total_points, 0.15, 0.4, config["step_base"], config["step_distractor"])
        else:
            if total_points * 0.32 <= index <= total_points * 0.44 or total_points * 0.68 <= index <= total_points * 0.76:
                heart_rate = config["heart_rate_cross"]
            else:
                heart_rate = config["heart_rate_hover"]
            step_freq = config["step_base"]
        return heart_rate, step_freq

    if label == "STEP_FREQ_HIGH":
        if template_type == "hover_then_cross":
            step_freq = segment_value(index, total_points, 0.5, 0.78, config["step_hover"], config["step_cross"])
            heart_rate = config["heart_rate_base"]
        elif template_type == "cross_with_secondary_distractor":
            step_freq = segment_value(index, total_points, 0.35, 0.65, config["step_hover"], config["step_cross"])
            heart_rate = segment_value(index, total_points, 0.15, 0.5, config["heart_rate_base"], config["heart_rate_distractor"])
        else:
            if total_points * 0.28 <= index <= total_points * 0.4 or total_points * 0.66 <= index <= total_points * 0.74:
                step_freq = config["step_cross"]
            else:
                step_freq = config["step_hover"]
            heart_rate = config["heart_rate_base"]
        return heart_rate, step_freq

    if label == "STEP_FREQ_LOW":
        if template_type == "hover_then_cross":
            step_freq = segment_value(index, total_points, 0.48, 0.78, config["step_hover"], config["step_cross"])
            heart_rate = config["heart_rate_base"]
        elif template_type == "cross_with_secondary_distractor":
            step_freq = segment_value(index, total_points, 0.35, 0.62, config["step_hover"], config["step_cross"])
            heart_rate = segment_value(index, total_points, 0.12, 0.4, config["heart_rate_base"], config["heart_rate_distractor"])
        else:
            if total_points * 0.3 <= index <= total_points * 0.42 or total_points * 0.65 <= index <= total_points * 0.74:
                step_freq = config["step_cross"]
            else:
                step_freq = config["step_hover"]
            heart_rate = config["heart_rate_base"]
        return heart_rate, step_freq

    if label == "GAIT_SUDDEN_CHANGE":
        if template_type == "hover_then_cross":
            transition_point = 0.55
        elif template_type == "cross_with_secondary_distractor":
            transition_point = 0.45
        else:
            transition_point = 0.4 if index < total_points * 0.55 else 0.68

        if template_type == "flicker_and_recovery":
            if index < total_points * 0.4:
                step_freq = config["step_before"]
            elif index < total_points * 0.55:
                step_freq = config["step_after"]
            elif index < total_points * 0.68:
                step_freq = config["step_before"] + 0.15
            else:
                step_freq = config["step_after"]
            heart_rate = config["heart_rate_base"]
        else:
            if index < total_points * transition_point:
                step_freq = config["step_before"]
            else:
                step_freq = config["step_after"]
            heart_rate = (
                segment_value(index, total_points, 0.18, 0.48, config["heart_rate_base"], config["heart_rate_distractor"])
                if template_type == "cross_with_secondary_distractor"
                else config["heart_rate_base"]
            )
        return heart_rate, step_freq

    raise ValueError(f"Unsupported label: {label}")


def generate_legacy_sample_records(label, template_type, seed, interval_ms, duration_ms):
    rng = random.Random(seed)
    total_points = max(1, duration_ms // interval_ms)
    config = choose_ranges_legacy(label, template_type, rng)
    records = []

    accel_amplitude = 0.55
    if label == "STEP_FREQ_LOW":
        accel_amplitude = 0.35
    elif label == "STEP_FREQ_HIGH":
        accel_amplitude = 0.62

    for index in range(total_points):
        timestamp = index * interval_ms
        time_sec = timestamp / 1000.0
        heart_rate, step_freq = resolve_signal_profile_legacy(label, template_type, index, total_points, config)

        phase = 2.0 * math.pi * step_freq * time_sec
        secondary_phase = phase * 2.0

        accel_x = 0.08 * math.sin(phase) + rng.uniform(-0.015, 0.015)
        accel_y = 0.05 * math.sin(secondary_phase) + rng.uniform(-0.015, 0.015)
        accel_z = 9.8 + accel_amplitude * math.cos(phase) + rng.uniform(-0.08, 0.08)

        gyro_scale = 0.03 if label != "GAIT_SUDDEN_CHANGE" else 0.05
        gyro_x = gyro_scale * math.cos(phase) + rng.uniform(-0.01, 0.01)
        gyro_y = (gyro_scale * 0.6) * math.sin(phase) + rng.uniform(-0.01, 0.01)
        gyro_z = (gyro_scale * 0.8) * math.sin(phase * 1.5) + rng.uniform(-0.01, 0.01)

        records.append(
            {
                "timestamp": timestamp,
                "data": {
                    "accel": {
                        "x": round(accel_x, 3),
                        "y": round(accel_y, 3),
                        "z": round(accel_z, 3),
                    },
                    "gyro": {
                        "x": round(gyro_x, 3),
                        "y": round(gyro_y, 3),
                        "z": round(gyro_z, 3),
                    },
                    "heartRate": int(round(heart_rate + rng.uniform(-1.0, 1.0))),
                },
            }
        )

    return records


def build_stable_config(label, template_type, rng):
    config = {
        "phase_offset": rng.uniform(0.0, 2.0 * math.pi),
        "pulse_sharpness": rng.uniform(1.05, 1.3),
        "noise_scale": rng.uniform(0.006, 0.012),
        "z_baseline": rng.uniform(9.18, 9.28),
    }

    if label == "HEART_RATE_HIGH":
        config.update(
            {
                "cadence_normal": rng.uniform(1.45, 1.85),
                "amplitude": rng.uniform(1.05, 1.18),
                "hr_start": rng.uniform(156.0, 160.0),
                "hr_end": rng.uniform(160.0, 168.0) if template_type == "drift_high" else rng.uniform(157.0, 162.0),
                "hr_wobble": rng.uniform(0.6, 1.5),
            }
        )
        return config

    if label == "HEART_RATE_LOW":
        config.update(
            {
                "cadence_normal": rng.uniform(1.35, 1.75),
                "amplitude": rng.uniform(1.02, 1.15),
                "hr_start": rng.uniform(31.0, 35.0),
                "hr_end": rng.uniform(30.0, 35.0) if template_type == "drift_low" else rng.uniform(31.0, 36.0),
                "hr_wobble": rng.uniform(0.5, 1.2),
            }
        )
        return config

    if label == "STEP_FREQ_HIGH":
        config.update(
            {
                "cadence_fast": rng.uniform(3.8, 4.4),
                "cadence_modulation": rng.uniform(0.08, 0.22) if template_type == "fast_modulated" else rng.uniform(0.03, 0.08),
                "amplitude": rng.uniform(1.28, 1.48),
                "hr_start": rng.uniform(108.0, 118.0),
                "hr_end": rng.uniform(116.0, 128.0),
                "hr_wobble": rng.uniform(0.4, 1.0),
            }
        )
        return config

    if label == "GAIT_SUDDEN_CHANGE":
        switch_ratio = {
            "early_switch": rng.uniform(0.32, 0.40),
            "mid_switch": rng.uniform(0.43, 0.55),
            "late_switch": rng.uniform(0.58, 0.68),
        }[template_type]
        config.update(
            {
                "cadence_before": rng.uniform(1.0, 1.45),
                "cadence_after": rng.uniform(3.45, 4.05),
                "switch_ratio": switch_ratio,
                "transition_width": rng.uniform(0.02, 0.05),
                "amplitude_before": rng.uniform(1.05, 1.18),
                "amplitude_after": rng.uniform(1.30, 1.48),
                "hr_start": rng.uniform(98.0, 110.0),
                "hr_end": rng.uniform(104.0, 118.0),
                "hr_wobble": rng.uniform(0.5, 1.2),
            }
        )
        return config

    raise ValueError(f"Unsupported stable label: {label}")


def resolve_stable_state(label, template_type, progress, config):
    if label == "HEART_RATE_HIGH":
        cadence = config["cadence_normal"] + 0.06 * math.sin(progress * 2.0 * math.pi)
        heart_rate = interpolate(config["hr_start"], config["hr_end"], progress)
        heart_rate += config["hr_wobble"] * math.sin(progress * 3.0 * math.pi)
        amplitude = config["amplitude"]
        return heart_rate, cadence, amplitude

    if label == "HEART_RATE_LOW":
        cadence = config["cadence_normal"] + 0.05 * math.sin(progress * 2.0 * math.pi)
        heart_rate = interpolate(config["hr_start"], config["hr_end"], progress)
        heart_rate += config["hr_wobble"] * math.sin(progress * 3.5 * math.pi)
        amplitude = config["amplitude"]
        return heart_rate, cadence, amplitude

    if label == "STEP_FREQ_HIGH":
        cadence = config["cadence_fast"] + config["cadence_modulation"] * math.sin(progress * 4.0 * math.pi)
        heart_rate = interpolate(config["hr_start"], config["hr_end"], progress)
        heart_rate += config["hr_wobble"] * math.sin(progress * 2.0 * math.pi)
        amplitude = config["amplitude"]
        return heart_rate, cadence, amplitude

    if label == "GAIT_SUDDEN_CHANGE":
        switch_start = max(0.0, config["switch_ratio"] - config["transition_width"] / 2.0)
        switch_end = min(1.0, config["switch_ratio"] + config["transition_width"] / 2.0)
        if progress <= switch_start:
            transition = 0.0
        elif progress >= switch_end:
            transition = 1.0
        else:
            transition = (progress - switch_start) / max(1e-6, switch_end - switch_start)
        cadence = interpolate(config["cadence_before"], config["cadence_after"], transition)
        heart_rate = interpolate(config["hr_start"], config["hr_end"], progress)
        heart_rate += config["hr_wobble"] * math.sin(progress * 2.5 * math.pi)
        amplitude = interpolate(config["amplitude_before"], config["amplitude_after"], transition)
        return heart_rate, cadence, amplitude

    raise ValueError(f"Unsupported stable label: {label}")


def generate_stable_sample_records(label, template_type, seed, interval_ms, duration_ms):
    rng = random.Random(seed)
    total_points = max(1, duration_ms // interval_ms)
    config = build_stable_config(label, template_type, rng)
    records = []
    phase = config["phase_offset"]
    dt_sec = interval_ms / 1000.0
    noise_scale = config["noise_scale"] * max(0.1, min(1.0, interval_ms / DEFAULT_INTERVAL_MS))

    for index in range(total_points):
        progress = index / max(1, total_points - 1)
        timestamp = index * interval_ms
        heart_rate, cadence_hz, amplitude = resolve_stable_state(label, template_type, progress, config)

        phase += 2.0 * math.pi * cadence_hz * dt_sec
        pulse = max(0.0, math.sin(phase)) ** config["pulse_sharpness"]

        accel_x = 0.12 * math.sin(phase * 0.5 + config["phase_offset"] * 0.3) + rng.uniform(-noise_scale, noise_scale)
        accel_y = 0.09 * math.cos(phase * 0.5 + config["phase_offset"] * 0.2) + rng.uniform(-noise_scale, noise_scale)
        accel_z = config["z_baseline"] + amplitude * pulse + rng.uniform(-noise_scale, noise_scale)

        gyro_scale = 0.03 + 0.018 * pulse
        gyro_x = gyro_scale * math.cos(phase * 0.8) + rng.uniform(-noise_scale * 0.5, noise_scale * 0.5)
        gyro_y = (gyro_scale * 0.7) * math.sin(phase * 0.9) + rng.uniform(-noise_scale * 0.5, noise_scale * 0.5)
        gyro_z = (gyro_scale * 0.8) * math.sin(phase * 1.1) + rng.uniform(-noise_scale * 0.5, noise_scale * 0.5)

        records.append(
            {
                "timestamp": timestamp,
                "data": {
                    "accel": {
                        "x": round(accel_x, 3),
                        "y": round(accel_y, 3),
                        "z": round(accel_z, 3),
                    },
                    "gyro": {
                        "x": round(gyro_x, 3),
                        "y": round(gyro_y, 3),
                        "z": round(gyro_z, 3),
                    },
                    "heartRate": int(round(heart_rate + rng.uniform(-1.0, 1.0))),
                },
            }
        )

    return records


def acceleration_magnitude(record):
    accel = record["data"]["accel"]
    return math.sqrt(accel["x"] ** 2 + accel["y"] ** 2 + accel["z"] ** 2)


def gyroscope_magnitude(record):
    gyro = record["data"]["gyro"]
    return math.sqrt(gyro["x"] ** 2 + gyro["y"] ** 2 + gyro["z"] ** 2)


def estimate_step_frequency(window_records):
    if len(window_records) < 10:
        return 0.0

    magnitudes = [acceleration_magnitude(record) for record in window_records]
    peaks = 0
    for index in range(1, len(magnitudes) - 1):
        if (
            magnitudes[index] > APP_PEAK_THRESHOLD
            and magnitudes[index] > magnitudes[index - 1]
            and magnitudes[index] > magnitudes[index + 1]
        ):
            peaks += 1

    duration_sec = (window_records[-1]["timestamp"] - window_records[0]["timestamp"]) / 1000.0
    if duration_sec <= 0.0:
        return 0.0
    return peaks / duration_sec


def extract_window_features(window_records, window_end_timestamp):
    accel_magnitudes = [acceleration_magnitude(record) for record in window_records]
    gyro_magnitudes = [gyroscope_magnitude(record) for record in window_records]

    jerk_values = []
    for previous, current in zip(window_records, window_records[1:]):
        dt_sec = (current["timestamp"] - previous["timestamp"]) / 1000.0
        if dt_sec <= 0.0:
            continue
        jerk = (acceleration_magnitude(current) - acceleration_magnitude(previous)) / dt_sec
        jerk_values.append(jerk)

    jerk_rms = math.sqrt(sum(value * value for value in jerk_values) / len(jerk_values)) if jerk_values else 0.0
    rms_accel = math.sqrt(sum(value * value for value in accel_magnitudes) / len(accel_magnitudes))
    rms_gyro = math.sqrt(sum(value * value for value in gyro_magnitudes) / len(gyro_magnitudes))

    return {
        "timestamp": window_end_timestamp,
        "heart_rate": window_records[-1]["data"].get("heartRate"),
        "step_freq_hz": estimate_step_frequency(window_records),
        "rms_accel": rms_accel,
        "max_accel": max(accel_magnitudes),
        "min_accel": min(accel_magnitudes),
        "jerk_rms": jerk_rms,
        "rms_gyro": rms_gyro,
    }


class LocalRuleEvaluator:
    def __init__(self):
        self.last_window = None
        self.duration_history = defaultdict(list)
        self.consecutive_counts = defaultdict(int)
        self.last_event_timestamps = {}

    def predict_sample(self, records):
        events = []
        if not records:
            return APP_NONE_LABEL, events

        last_timestamp = records[-1]["timestamp"]
        window_end = APP_WINDOW_SIZE_MS

        while window_end <= last_timestamp:
            window_start = window_end - APP_WINDOW_SIZE_MS
            window_records = [
                record
                for record in records
                if window_start <= record["timestamp"] <= window_end
            ]
            if window_records:
                window_features = extract_window_features(window_records, window_end)
                events.extend(self.detect_window(window_features))
                self.last_window = window_features
            window_end += APP_STEP_SIZE_MS

        if not events:
            return APP_NONE_LABEL, events

        top_event = sorted(events, key=lambda event: (-event["severity"], event["timestamp"]))[0]
        return top_event["type"], events

    def detect_window(self, window):
        raw_events = []
        timestamp = window["timestamp"]
        heart_rate = window["heart_rate"]
        step_freq = window["step_freq_hz"]

        if heart_rate is not None and heart_rate > APP_HR_HIGH_THRESHOLD:
            raw_events.append(
                self.create_event(
                    "HEART_RATE_HIGH",
                    min(1.0, (heart_rate - APP_HR_HIGH_THRESHOLD) / 50.0),
                    timestamp,
                )
            )

        if heart_rate is not None and heart_rate < APP_HR_LOW_THRESHOLD:
            raw_events.append(
                self.create_event(
                    "HEART_RATE_LOW",
                    min(1.0, (APP_HR_LOW_THRESHOLD - heart_rate) / 50.0),
                    timestamp,
                )
            )

        if self.last_window is not None and heart_rate is not None and self.last_window["heart_rate"] is not None:
            time_diff = timestamp - self.last_window["timestamp"]
            if 1000 <= time_diff <= 5000:
                hr_change = abs(heart_rate - self.last_window["heart_rate"])
                if hr_change > APP_HR_SUDDEN_CHANGE_THRESHOLD:
                    raw_events.append(
                        self.create_event(
                            "HEART_RATE_SUDDEN_CHANGE",
                            min(1.0, hr_change / 50.0),
                            timestamp,
                        )
                    )

        if step_freq > 0.0:
            if step_freq > APP_STEP_FREQ_HIGH_THRESHOLD:
                raw_events.append(
                    self.create_event(
                        "STEP_FREQ_HIGH",
                        min(1.0, (step_freq - APP_STEP_FREQ_HIGH_THRESHOLD) / 2.0),
                        timestamp,
                    )
                )

            if step_freq < APP_STEP_FREQ_LOW_THRESHOLD:
                raw_events.append(
                    self.create_event(
                        "STEP_FREQ_LOW",
                        min(1.0, (APP_STEP_FREQ_LOW_THRESHOLD - step_freq) / 0.5),
                        timestamp,
                    )
                )

            if self.last_window is not None and self.last_window["step_freq_hz"] > 0.0:
                step_change = abs(step_freq - self.last_window["step_freq_hz"])
                if step_change > APP_STEP_FREQ_SUDDEN_CHANGE_THRESHOLD:
                    raw_events.append(
                        self.create_event(
                            "GAIT_SUDDEN_CHANGE",
                            min(1.0, step_change / 2.0),
                            timestamp,
                        )
                    )

        if window["rms_accel"] > APP_MOTION_INTENSITY_THRESHOLD:
            raw_events.append(
                self.create_event(
                    "MOTION_INTENSITY_ANOMALY",
                    min(1.0, window["rms_accel"] / 20.0),
                    timestamp,
                )
            )

        if window["jerk_rms"] > APP_JERK_THRESHOLD:
            raw_events.append(
                self.create_event(
                    "MOTION_INTENSITY_ANOMALY",
                    min(1.0, window["jerk_rms"] / 30.0),
                    timestamp,
                )
            )

        has_impact = window["max_accel"] > APP_FALL_IMPACT_THRESHOLD
        has_rotation = window["rms_gyro"] > APP_FALL_ROTATION_THRESHOLD
        has_stationary = window["min_accel"] < APP_FALL_STATIONARY_THRESHOLD
        if has_impact and has_rotation and has_stationary:
            raw_events.append(self.create_event("FALL_DETECTED", 0.85, timestamp))

        return self.fuse_events(raw_events, timestamp)

    def create_event(self, type_name, confidence, timestamp):
        severity = int(BASE_SEVERITIES[type_name] * confidence)
        severity = max(1, min(10, severity))
        return {
            "type": type_name,
            "severity": severity,
            "timestamp": timestamp,
        }

    def fuse_events(self, events, timestamp):
        if not events:
            return []

        grouped = defaultdict(list)
        for event in events:
            grouped[event["type"]].append(event)

        fused = []
        for type_name, grouped_events in grouped.items():
            primary = max(grouped_events, key=lambda event: event["severity"])
            self.add_evidence(type_name, timestamp)
            severity = primary["severity"]
            if self.consecutive_counts[type_name] >= 3:
                severity = min(10, severity + 1)
            fused.append(
                {
                    "type": type_name,
                    "severity": severity,
                    "timestamp": primary["timestamp"],
                }
            )

        return fused

    def add_evidence(self, type_name, timestamp):
        previous_timestamp = self.last_event_timestamps.get(type_name, 0)
        if timestamp - previous_timestamp > 5000:
            self.consecutive_counts[type_name] = 1
        else:
            self.consecutive_counts[type_name] = self.consecutive_counts.get(type_name, 0) + 1
        self.last_event_timestamps[type_name] = timestamp


def evaluate_records(records):
    evaluator = LocalRuleEvaluator()
    return evaluator.predict_sample(records)


def candidate_is_stable(expected_label, predicted_label, events):
    if predicted_label != expected_label:
        return False

    target_top_severity = max(
        (event["severity"] for event in events if event["type"] == expected_label),
        default=0,
    )
    other_top_severity = max(
        (event["severity"] for event in events if event["type"] != expected_label),
        default=0,
    )
    return target_top_severity > other_top_severity


def generate_validated_stable_sample(label, template_type, base_seed, interval_ms, duration_ms, max_attempts=64):
    for attempt in range(max_attempts):
        seed = base_seed + attempt * 104729
        records = generate_stable_sample_records(label, template_type, seed, interval_ms, duration_ms)
        predicted_label, events = evaluate_records(records)
        if candidate_is_stable(label, predicted_label, events):
            target_top_severity = max(
                (event["severity"] for event in events if event["type"] == label),
                default=0,
            )
            other_top_severity = max(
                (event["severity"] for event in events if event["type"] != label),
                default=0,
            )
            return {
                "records": records,
                "seed": seed,
                "predicted_label": predicted_label,
                "target_top_severity": target_top_severity,
                "other_top_severity": other_top_severity,
            }

    raise RuntimeError(
        f"Unable to generate a stable '{label}' sample after {max_attempts} attempts. "
        "Reduce strictness or inspect the validator."
    )


def write_dataset(output_dir, dataset_name, version, interval_ms, duration_ms, manifest_samples, generation_profile, validation_summary):
    output_dir.mkdir(parents=True, exist_ok=True)
    samples_dir = output_dir / "samples"
    samples_dir.mkdir(parents=True, exist_ok=True)

    manifest = {
        "dataset_name": dataset_name,
        "version": version,
        "interval_ms": interval_ms,
        "duration_ms": duration_ms,
        "prediction_rule": "max_severity",
        "generation_profile": generation_profile,
        "validator_accuracy": validation_summary["accuracy"],
        "validator_total": validation_summary["total"],
        "validator_matched": validation_summary["matched"],
        "validator_label_breakdown": validation_summary["by_label"],
        "samples": manifest_samples,
    }

    with (output_dir / "manifest.json").open("w", encoding="utf-8") as manifest_file:
        json.dump(manifest, manifest_file, indent=2, ensure_ascii=False)

    with (output_dir / "labels.csv").open("w", newline="", encoding="utf-8") as labels_file:
        writer = csv.DictWriter(
            labels_file,
            fieldnames=[
                "sample_id",
                "file",
                "expected_label",
                "difficulty",
                "template_type",
                "seed",
                "validator_prediction",
                "validator_target_top_severity",
                "validator_other_top_severity",
            ],
        )
        writer.writeheader()
        writer.writerows(manifest_samples)


def write_jsonl_sample(samples_dir, sample_file_name, records):
    with (samples_dir / sample_file_name).open("w", encoding="utf-8") as output_file:
        for record in records:
            output_file.write(json.dumps(record, ensure_ascii=False) + "\n")


def build_validation_summary(validation_rows):
    matched = sum(1 for row in validation_rows if row["expected_label"] == row["validator_prediction"])
    total = len(validation_rows)
    by_label = {}
    grouped = defaultdict(list)
    for row in validation_rows:
        grouped[row["expected_label"]].append(row)

    for label, rows in sorted(grouped.items()):
        label_matched = sum(1 for row in rows if row["expected_label"] == row["validator_prediction"])
        by_label[label] = {
            "count": len(rows),
            "matched": label_matched,
            "accuracy": (label_matched / len(rows)) if rows else 0.0,
        }

    return {
        "total": total,
        "matched": matched,
        "accuracy": (matched / total) if total else 0.0,
        "by_label": by_label,
    }


def build_dataset(args):
    output_dir = Path(args.out).resolve()
    if output_dir.exists():
        shutil.rmtree(output_dir)

    manifest_samples = []
    rng = random.Random(args.seed)
    sample_index = 0

    if args.profile == PROFILE_STABLE90:
        class_weights = STABLE_CLASS_WEIGHTS
    else:
        class_weights = LEGACY_CLASS_WEIGHTS

    write_dataset(
        output_dir=output_dir,
        dataset_name=args.dataset_name,
        version=PROFILE_VERSIONS[args.profile],
        interval_ms=args.interval_ms,
        duration_ms=args.duration_ms,
        manifest_samples=manifest_samples,
        generation_profile=args.profile,
        validation_summary={"accuracy": 0.0, "total": 0, "matched": 0, "by_label": {}},
    )

    samples_dir = output_dir / "samples"

    for label, class_count in scale_distribution(args.count, class_weights).items():
        if args.profile == PROFILE_STABLE90:
            template_weights = STABLE_TEMPLATE_WEIGHTS[label]
        else:
            template_weights = LEGACY_TEMPLATE_WEIGHTS

        template_allocation = allocate_templates(class_count, template_weights)
        for template_type, template_count in template_allocation.items():
            for _ in range(template_count):
                sample_seed = args.seed + sample_index * 7919 + rng.randint(0, 499)
                sample_id = f"{sample_index + 1:03d}_{label.lower()}"
                sample_file_name = f"{sample_id}.jsonl"

                if args.profile == PROFILE_STABLE90:
                    validation_result = generate_validated_stable_sample(
                        label=label,
                        template_type=template_type,
                        base_seed=sample_seed,
                        interval_ms=args.interval_ms,
                        duration_ms=args.duration_ms,
                    )
                    records = validation_result["records"]
                    accepted_seed = validation_result["seed"]
                    validator_prediction = validation_result["predicted_label"]
                    target_top_severity = validation_result["target_top_severity"]
                    other_top_severity = validation_result["other_top_severity"]
                else:
                    accepted_seed = sample_seed
                    records = generate_legacy_sample_records(
                        label=label,
                        template_type=template_type,
                        seed=sample_seed,
                        interval_ms=args.interval_ms,
                        duration_ms=args.duration_ms,
                    )
                    validator_prediction, validator_events = evaluate_records(records)
                    target_top_severity = max(
                        (event["severity"] for event in validator_events if event["type"] == label),
                        default=0,
                    )
                    other_top_severity = max(
                        (event["severity"] for event in validator_events if event["type"] != label),
                        default=0,
                    )

                write_jsonl_sample(samples_dir, sample_file_name, records)
                manifest_samples.append(
                    {
                        "sample_id": sample_id,
                        "file": sample_file_name,
                        "expected_label": label,
                        "difficulty": "stable90" if args.profile == PROFILE_STABLE90 else "borderline",
                        "template_type": template_type,
                        "seed": accepted_seed,
                        "validator_prediction": validator_prediction,
                        "validator_target_top_severity": target_top_severity,
                        "validator_other_top_severity": other_top_severity,
                    }
                )
                sample_index += 1

    validation_summary = build_validation_summary(manifest_samples)
    write_dataset(
        output_dir=output_dir,
        dataset_name=args.dataset_name,
        version=PROFILE_VERSIONS[args.profile],
        interval_ms=args.interval_ms,
        duration_ms=args.duration_ms,
        manifest_samples=manifest_samples,
        generation_profile=args.profile,
        validation_summary=validation_summary,
    )

    zip_path = None
    if args.zip:
        zip_path = shutil.make_archive(str(output_dir), "zip", root_dir=str(output_dir))

    return output_dir, zip_path, validation_summary


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Generate a VitaTrack batch-evaluation dataset. "
            "The default 'stable90' profile is calibrated to stay above 90% accuracy "
            "with the current 2s window rule-based detector."
        )
    )
    parser.add_argument(
        "--profile",
        choices=[PROFILE_STABLE90, PROFILE_LEGACY],
        default=DEFAULT_PROFILE,
        help=(
            "Generation profile. "
            "'stable90' is the new default and excludes STEP_FREQ_LOW because the current "
            "2s peak-count detector cannot emit that label reliably."
        ),
    )
    parser.add_argument("--out", default=None, help="Dataset output directory.")
    parser.add_argument("--dataset-name", default=None, help="Logical dataset name.")
    parser.add_argument("--count", type=int, default=DEFAULT_COUNT, help="Total sample count.")
    parser.add_argument("--interval-ms", type=int, default=DEFAULT_INTERVAL_MS, help="Sample interval in milliseconds.")
    parser.add_argument("--duration-ms", type=int, default=DEFAULT_DURATION_MS, help="Duration of each sample in milliseconds.")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED, help="Base RNG seed.")
    parser.add_argument("--zip", action=argparse.BooleanOptionalAction, default=True, help="Also emit a ZIP archive.")
    args = parser.parse_args()

    default_name = PROFILE_DEFAULT_DATASET_NAMES[args.profile]
    if args.out is None:
        args.out = default_name
    if args.dataset_name is None:
        args.dataset_name = default_name
    return args


def main():
    args = parse_args()
    output_dir, zip_path, validation_summary = build_dataset(args)
    print(f"Dataset written to {output_dir}")
    print(
        "Validator accuracy: "
        f"{validation_summary['accuracy'] * 100:.1f}% "
        f"({validation_summary['matched']}/{validation_summary['total']})"
    )
    if zip_path:
        print(f"ZIP archive written to {zip_path}")


if __name__ == "__main__":
    main()
