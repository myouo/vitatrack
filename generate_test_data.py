import argparse
import json
import math
import random

def generate_sensor_data(profile, index, interval_ms):
    # Calculate time in seconds
    time_sec = (index * interval_ms) / 1000.0
    
    # Defaults
    accel = {"x": 0.0, "y": 0.0, "z": 9.8}
    gyro = {"x": 0.0, "y": 0.0, "z": 0.0}
    hr = 80
    
    # Base walking motion parameters (sine waves)
    # normal step freq ~ 1.5 Hz
    step_freq = 1.5 
    
    if profile == "stable_walk":
        hr = 80 + int(2 * math.sin(time_sec / 10)) + random.randint(-1, 1)
    elif profile == "hr_high":
        hr = random.randint(195, 200) if index > 20 else random.randint(80, 85)
    elif profile == "hr_low":
        hr = random.randint(40, 45) if index > 20 else random.randint(80, 85)
    elif profile == "step_freq_high":
        step_freq = 3.5
        hr = 110 + int(time_sec) % 10
    elif profile == "step_freq_low":
        step_freq = 0.5
        hr = 70 + random.randint(-2, 2)
    elif profile == "step_freq_sudden_change":
        step_freq = 1.5 if index < 40 else 3.5
        hr = 80 if index < 40 else 130 + random.randint(-2, 2)
    
    # Add walking oscillation based on freq
    phase = 2 * math.pi * step_freq * time_sec
    
    accel["x"] = round(0.05 * math.sin(phase) + random.uniform(-0.02, 0.02), 2)
    accel["y"] = round(0.02 * math.sin(phase * 2) + random.uniform(-0.01, 0.01), 2)
    accel["z"] = round(9.8 + 0.5 * math.cos(phase) + random.uniform(-0.1, 0.1), 2)
    
    gyro["x"] = round(0.02 * math.cos(phase) + random.uniform(-0.01, 0.01), 2)
    gyro["y"] = round(0.01 * math.sin(phase) + random.uniform(-0.01, 0.01), 2)
    gyro["z"] = round(0.03 * math.sin(phase * 1.5) + random.uniform(-0.01, 0.01), 2)

    return {
        "accel": accel,
        "gyro": gyro,
        "heartRate": int(hr)
    }

def main():
    parser = argparse.ArgumentParser(description="VitaTrack Sensor Stream Test Data Generator")
    parser.add_argument('--type', type=str, default="stable_walk",
                        choices=["stable_walk", "hr_high", "hr_low", "step_freq_high", "step_freq_low", "step_freq_sudden_change"],
                        help="Type of data stream anomaly profile to generate.")
    parser.add_argument('--count', type=int, default=200,
                        help="Number of data points (lines) to generate.")
    parser.add_argument('--interval', type=int, default=200,
                        help="Time interval between data points in milliseconds.")
    parser.add_argument('--output', type=str, default='output.jsonl',
                        help="Output file format (.jsonl or .csv).")
    
    args = parser.parse_args()
    
    data_list = []
    
    for i in range(args.count):
        timestamp = i * args.interval
        sensor_data = generate_sensor_data(args.type, i, args.interval)
        
        record = {
            "timestamp": timestamp,
            "data": sensor_data
        }
        data_list.append(record)
        
    if args.output.endswith('.csv'):
        import csv
        with open(args.output, 'w', newline='', encoding='utf-8') as f:
            # Flatten the dictionary for CSV
            fieldnames = ["timestamp", "accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z", "heartRate"]
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for r in data_list:
                flat_row = {
                    "timestamp": r["timestamp"],
                    "accel_x": r["data"]["accel"]["x"],
                    "accel_y": r["data"]["accel"]["y"],
                    "accel_z": r["data"]["accel"]["z"],
                    "gyro_x": r["data"]["gyro"]["x"],
                    "gyro_y": r["data"]["gyro"]["y"],
                    "gyro_z": r["data"]["gyro"]["z"],
                    "heartRate": r["data"]["heartRate"],
                }
                writer.writerow(flat_row)
    else:
        # Default to jsonl
        with open(args.output, 'w', encoding='utf-8') as f:
            for r in data_list:
                f.write(json.dumps(r) + "\n")
                
    print(f"Successfully generated {args.count} records of type '{args.type}' to {args.output}")

if __name__ == "__main__":
    main()
