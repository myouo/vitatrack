import argparse
import csv
import json
import math
import random
import shutil
from pathlib import Path


DEFAULT_COUNT = 500
DEFAULT_INTERVAL_MS = 50
DEFAULT_DURATION_MS = 5000
DEFAULT_DATASET_NAME = "borderline_batch_eval_v1"
DEFAULT_VERSION = "1.0"
DEFAULT_SEED = 20260416

CLASS_WEIGHTS = {
    "HEART_RATE_HIGH": 140,
    "HEART_RATE_LOW": 80,
    "STEP_FREQ_HIGH": 80,
    "STEP_FREQ_LOW": 60,
    "GAIT_SUDDEN_CHANGE": 140,
}

TEMPLATE_WEIGHTS = {
    "hover_then_cross": 0.50,
    "cross_with_secondary_distractor": 0.30,
    "flicker_and_recovery": 0.20,
}


def scale_distribution(total_count):
    total_weight = sum(CLASS_WEIGHTS.values())
    scaled = {}
    remainders = []
    assigned = 0

    for label, weight in CLASS_WEIGHTS.items():
        exact = total_count * weight / total_weight
        whole = int(exact)
        scaled[label] = whole
        assigned += whole
        remainders.append((exact - whole, label))

    for _, label in sorted(remainders, reverse=True)[: total_count - assigned]:
        scaled[label] += 1

    return scaled


def allocate_templates(class_count):
    allocated = {}
    assigned = 0
    remainders = []

    for template_name, weight in TEMPLATE_WEIGHTS.items():
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


def segment_value(index, total_points, start_ratio, end_ratio, low_value, high_value):
    start_index = int(total_points * start_ratio)
    end_index = int(total_points * end_ratio)
    if index <= start_index:
        return low_value
    if index >= end_index:
        return high_value
    progress = (index - start_index) / max(1, end_index - start_index)
    return low_value + (high_value - low_value) * smooth_transition(progress)


def choose_ranges(label, template_type, rng):
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


def resolve_signal_profile(label, template_type, index, total_points, config):
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


def generate_sample_records(label, template_type, seed, interval_ms, duration_ms):
    rng = random.Random(seed)
    total_points = max(1, duration_ms // interval_ms)
    config = choose_ranges(label, template_type, rng)
    records = []

    accel_amplitude = 0.55
    if label == "STEP_FREQ_LOW":
        accel_amplitude = 0.35
    elif label == "STEP_FREQ_HIGH":
        accel_amplitude = 0.62

    for index in range(total_points):
        timestamp = index * interval_ms
        time_sec = timestamp / 1000.0
        heart_rate, step_freq = resolve_signal_profile(label, template_type, index, total_points, config)

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


def write_dataset(output_dir, dataset_name, version, interval_ms, duration_ms, manifest_samples):
    output_dir.mkdir(parents=True, exist_ok=True)
    samples_dir = output_dir / "samples"
    samples_dir.mkdir(parents=True, exist_ok=True)

    manifest = {
        "dataset_name": dataset_name,
        "version": version,
        "interval_ms": interval_ms,
        "duration_ms": duration_ms,
        "prediction_rule": "max_severity",
        "samples": manifest_samples,
    }

    with (output_dir / "manifest.json").open("w", encoding="utf-8") as manifest_file:
        json.dump(manifest, manifest_file, indent=2, ensure_ascii=False)

    with (output_dir / "labels.csv").open("w", newline="", encoding="utf-8") as labels_file:
        writer = csv.DictWriter(
            labels_file,
            fieldnames=["sample_id", "file", "expected_label", "difficulty", "template_type", "seed"],
        )
        writer.writeheader()
        writer.writerows(manifest_samples)


def write_jsonl_sample(samples_dir, sample_file_name, records):
    with (samples_dir / sample_file_name).open("w", encoding="utf-8") as output_file:
        for record in records:
            output_file.write(json.dumps(record, ensure_ascii=False) + "\n")


def build_dataset(args):
    rng = random.Random(args.seed)
    output_dir = Path(args.out).resolve()
    if output_dir.exists():
        shutil.rmtree(output_dir)

    manifest_samples = []
    write_dataset(
        output_dir=output_dir,
        dataset_name=args.dataset_name,
        version=DEFAULT_VERSION,
        interval_ms=args.interval_ms,
        duration_ms=args.duration_ms,
        manifest_samples=manifest_samples,
    )
    samples_dir = output_dir / "samples"

    sample_index = 0
    for label, class_count in scale_distribution(args.count).items():
        template_allocation = allocate_templates(class_count)
        for template_type, template_count in template_allocation.items():
            for _ in range(template_count):
                sample_seed = args.seed + sample_index * 7919 + rng.randint(0, 499)
                sample_id = f"{sample_index + 1:03d}_{label.lower()}"
                sample_file_name = f"{sample_id}.jsonl"
                records = generate_sample_records(
                    label=label,
                    template_type=template_type,
                    seed=sample_seed,
                    interval_ms=args.interval_ms,
                    duration_ms=args.duration_ms,
                )
                write_jsonl_sample(samples_dir, sample_file_name, records)
                manifest_samples.append(
                    {
                        "sample_id": sample_id,
                        "file": sample_file_name,
                        "expected_label": label,
                        "difficulty": "borderline",
                        "template_type": template_type,
                        "seed": sample_seed,
                    }
                )
                sample_index += 1

    write_dataset(
        output_dir=output_dir,
        dataset_name=args.dataset_name,
        version=DEFAULT_VERSION,
        interval_ms=args.interval_ms,
        duration_ms=args.duration_ms,
        manifest_samples=manifest_samples,
    )

    zip_path = None
    if args.zip:
        zip_path = shutil.make_archive(str(output_dir), "zip", root_dir=str(output_dir))

    return output_dir, zip_path


def parse_args():
    parser = argparse.ArgumentParser(
        description="Generate a VitaTrack borderline batch-evaluation dataset."
    )
    parser.add_argument("--out", default=DEFAULT_DATASET_NAME, help="Dataset output directory.")
    parser.add_argument("--dataset-name", default=DEFAULT_DATASET_NAME, help="Logical dataset name.")
    parser.add_argument("--count", type=int, default=DEFAULT_COUNT, help="Total sample count.")
    parser.add_argument("--interval-ms", type=int, default=DEFAULT_INTERVAL_MS, help="Sample interval in milliseconds.")
    parser.add_argument("--duration-ms", type=int, default=DEFAULT_DURATION_MS, help="Duration of each sample in milliseconds.")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED, help="Base RNG seed.")
    parser.add_argument("--zip", action=argparse.BooleanOptionalAction, default=True, help="Also emit a ZIP archive.")
    return parser.parse_args()


def main():
    args = parse_args()
    output_dir, zip_path = build_dataset(args)
    print(f"Dataset written to {output_dir}")
    if zip_path:
        print(f"ZIP archive written to {zip_path}")


if __name__ == "__main__":
    main()
