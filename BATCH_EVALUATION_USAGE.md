# VitaTrack 批量临界样本评测使用说明

## 1. 目标

这个方案分成两部分：

1. 用脚本生成 500 组“接近阈值、容易误判”的带标签测试数据。
2. 在 App 中按文件流顺序逐个回放这些样本，输出单样本结果和总体准确率。

当前实现已经支持：

- 生成带标签的批量数据集
- App 从文件夹导入数据集
- App 从 `.zip` 导入数据集
- 串行回放全部样本
- 输出每个样本的 `expected_label` / `predicted_label`
- 输出总体 accuracy、各类 precision/recall、confusion matrix
- 导出 JSON 和 CSV 报告

当前未支持：

- `.tar.gz`

## 2. 快速开始

在项目根目录执行：

```powershell
py -3 generate_borderline_dataset.py
```

默认会生成：

- 数据集目录：`borderline_batch_eval_v1`
- ZIP 包：`borderline_batch_eval_v1.zip`
- 总样本数：`500`
- 采样间隔：`50 ms`
- 单样本时长：`5000 ms`

然后在 App 中：

1. 进入 Dashboard 页面。
2. 点击 `Load Dataset`。
3. 选择导入方式：
   - `Folder`
   - `ZIP Archive`
4. 选中刚生成的数据集目录，或者对应的 ZIP 文件。
5. App 会自动开始批量回放和评测。
6. 评测完成后点击 `View Results` 查看结果。
7. 在结果页点击 `Export JSON and CSV` 导出报告。

## 3. 脚本说明

脚本文件：

- `generate_borderline_dataset.py`

命令行帮助：

```powershell
py -3 generate_borderline_dataset.py --help
```

参数说明：

- `--out`
  - 输出目录名
  - 默认值：`borderline_batch_eval_v1`
- `--dataset-name`
  - 写入 `manifest.json` 的逻辑数据集名称
  - 默认值：`borderline_batch_eval_v1`
- `--count`
  - 样本总数
  - 默认值：`500`
- `--interval-ms`
  - 样本时间间隔，单位毫秒
  - 默认值：`50`
- `--duration-ms`
  - 每个样本文件覆盖的时长，单位毫秒
  - 默认值：`5000`
- `--seed`
  - 随机种子
  - 默认值：`20260416`
- `--zip` / `--no-zip`
  - 是否额外生成 ZIP 包
  - 默认值：`--zip`

常用示例：

```powershell
# 生成默认 500 组测试集
py -3 generate_borderline_dataset.py
```

```powershell
# 指定输出目录和数据集名称
py -3 generate_borderline_dataset.py --out borderline_500 --dataset-name borderline_500
```

```powershell
# 生成更短的调试集，不打 ZIP
py -3 generate_borderline_dataset.py --out debug_set --count 20 --duration-ms 3000 --no-zip
```

```powershell
# 进一步缩小 interval，做高频边界测试
py -3 generate_borderline_dataset.py --out hi_res_eval --interval-ms 20 --count 500
```

## 4. 生成数据的设计

### 4.1 标签类别

脚本当前固定生成以下 5 类标签：

- `HEART_RATE_HIGH`
- `HEART_RATE_LOW`
- `STEP_FREQ_HIGH`
- `STEP_FREQ_LOW`
- `GAIT_SUDDEN_CHANGE`

### 4.2 默认分布

当 `--count 500` 时，样本分布为：

- `HEART_RATE_HIGH`: 140
- `HEART_RATE_LOW`: 80
- `STEP_FREQ_HIGH`: 80
- `STEP_FREQ_LOW`: 60
- `GAIT_SUDDEN_CHANGE`: 140

### 4.3 “临界且模糊”的实现方式

每类样本不是简单固定超阈值，而是用 3 种模板制造边界感：

- `hover_then_cross`
  - 先在阈值附近徘徊，再轻微越界
- `cross_with_secondary_distractor`
  - 主标签轻微越界，同时叠加次级干扰信号
- `flicker_and_recovery`
  - 短时间触发、恢复、再次触发

这样做的目的，是让检测更接近真实场景里的“模糊边界”输入，而不是过于理想化的纯净异常。

## 5. 输出目录结构

生成后的目录结构如下：

```text
borderline_batch_eval_v1/
  manifest.json
  labels.csv
  samples/
    001_heart_rate_high.jsonl
    002_heart_rate_high.jsonl
    ...
```

文件说明：

- `manifest.json`
  - App 批量导入的主入口
  - 记录数据集名称、间隔、时长、样本列表和标签
- `labels.csv`
  - 便于人工检查、离线统计或额外脚本处理
- `samples/*.jsonl`
  - 每个文件代表 1 个独立测试样本
  - App 会逐个读取并按文件流回放

如果启用了 `--zip`，还会额外生成：

```text
borderline_batch_eval_v1.zip
```

## 6. 数据格式

### 6.1 manifest.json

核心字段：

- `dataset_name`
- `version`
- `interval_ms`
- `duration_ms`
- `prediction_rule`
- `samples[]`

每个 `samples[]` 元素包含：

- `sample_id`
- `file`
- `expected_label`
- `difficulty`
- `template_type`
- `seed`

### 6.2 单个 JSONL 样本

每一行是一个时间点：

```json
{"timestamp":0,"data":{"accel":{"x":0.01,"y":-0.02,"z":10.03},"gyro":{"x":0.02,"y":0.01,"z":-0.01},"heartRate":149}}
```

字段说明：

- `timestamp`
  - 相对时间戳，单位毫秒
- `data.accel`
  - 三轴加速度
- `data.gyro`
  - 三轴陀螺仪
- `data.heartRate`
  - 心率 BPM

## 7. App 侧使用流程

### 7.1 导入方式

Dashboard 当前支持两种导入：

- 文件夹导入
- ZIP 导入

对应流程：

1. 点击 `Load Dataset`
2. 选择 `Folder` 或 `ZIP Archive`
3. 选中包含 `manifest.json` 和 `samples/` 的数据集
4. App 解析数据集后自动开始批量评测

文件夹导入要求：

- 根目录下必须有 `manifest.json`
- 根目录下必须有 `samples/`
- `samples/` 中必须存在 `manifest.json` 里声明的所有文件

ZIP 导入要求：

- ZIP 内必须包含 `manifest.json`
- ZIP 内必须包含 `samples/`
- ZIP 支持根目录直接放数据集，也支持外面再包一层目录

### 7.2 批量评测过程

App 会按 `manifest.json` 中 `samples[]` 的顺序：

1. 加载单个样本文件
2. 以文件流方式回放
3. 按现有窗口处理逻辑提取特征
4. 调用异常检测引擎
5. 汇总该样本的检测结果
6. 继续下一个样本

### 7.3 预测标签规则

当前实现里，单个样本的预测标签规则是：

1. 取该样本中所有检测到的异常事件
2. 选择 `severity` 最高的异常类型作为 `predicted_label`
3. 如果 `severity` 相同，选择时间更早的事件
4. 如果整个样本没有异常，则预测为 `NONE`

## 8. 结果怎么看

结果页会显示：

- 数据集名称
- 开始时间 / 结束时间
- 总体准确率 `Accuracy`
- `NONE` 预测次数
- 每个类别的 `precision` / `recall` / `support`
- confusion matrix
- 每个样本的明细结果

每个样本的明细至少包含：

- `sample_id`
- `expected_label`
- `predicted_label`
- 是否命中 `matched`
- `top_severity`
- `detected_types`
- `elapsed_ms`
- `error`

## 9. 报告导出

在结果页点击 `Export JSON and CSV` 后，App 会导出两份报告：

- `batch_evaluation_时间戳.json`
- `batch_evaluation_时间戳.csv`

导出目录：

- Android 10 及以上：`Downloads/VitaTrackEvaluations`
- 旧版本系统：公共下载目录下的 `VitaTrackEvaluations`

## 10. 推荐操作流程

建议你按下面的方式使用：

1. 先用默认参数生成 500 组数据，确认主流程通。
2. 先在 App 里用 `Folder` 方式导入，定位数据结构问题更直接。
3. 再测试 `ZIP` 导入，验证压缩包流程。
4. 查看结果页的 accuracy、每类 recall 和 confusion matrix。
5. 如果某一类误判多，再定向调：
   - 阈值
   - 窗口参数
   - 检测优先级
6. 每次改算法后，复用同一批数据重新跑，保证对比可重复。

## 11. 已知限制

- 当前只支持 `Folder` 和 `.zip`
- 还没有接入 `.tar.gz`
- 当前环境下未完成一次完整 Android 构建校验，因为 Gradle 依赖下载受限
- 脚本已本地验证可生成数据集，但 App 侧改动主要做了静态核对

## 12. 相关文件

- `generate_borderline_dataset.py`
- `app/src/main/java/com/example/healthanomaly/core/BatchEvaluationManager.kt`
- `app/src/main/java/com/example/healthanomaly/data/dataset/BatchDatasetLoader.kt`
- `app/src/main/java/com/example/healthanomaly/presentation/dashboard/DashboardFragment.kt`
- `app/src/main/java/com/example/healthanomaly/presentation/results/BatchResultsActivity.kt`
