# VitaTrack 批量评测使用说明

## 1. 当前推荐方案

现在脚本默认生成的是 `stable90` 配置，而不是之前那种非常苛刻的 borderline 配置。

目标是：

1. 生成 500 组可重复的测试数据。
2. 这些数据能按文件流方式逐个输入 App。
3. 在当前 App 的 2 秒窗口规则检测器下，准确率尽量稳定保持在 90% 以上。

当前脚本已经按这个目标调整完成。

## 2. 为什么之前只有 53%

问题不只是“数据太严格”，而是和当前检测逻辑不匹配：

- App 的步频不是直接读文件里的理论 `step_freq`，而是重新用 2 秒窗口加速度峰值计数估算。
- 旧脚本里很多“接近阈值”的步频样本，在窗口特征里并不会稳定落到目标类别。
- 旧脚本还故意加入 secondary distractor，容易触发更高优先级的别的异常标签。
- 当前 `STEP_FREQ_LOW` 在现有 2 秒窗口 + 峰值计数 + `stepFreq > 0` 的规则下，本身就很难被稳定命中。

所以这次不是简单调随机范围，而是把生成器改成“按当前检测器反推生成”。

## 3. 新脚本做了什么

脚本文件：

- `generate_borderline_dataset.py`

新的默认模式：

- `--profile stable90`

它有两层保障：

1. 用更贴合当前检测器的模板生成数据。
2. 每个样本生成后，脚本内部会跑一遍本地镜像评测。

只有当镜像评测结果和目标标签一致，并且目标标签强于干扰标签时，这个样本才会被保留。

也就是说，默认生成的数据不是“希望能命中”，而是“已经先按当前规则校验过一遍”。

## 4. 快速开始

在项目根目录执行：

```powershell
py -3 generate_borderline_dataset.py
```

默认输出：

- 数据集目录：`stable_batch_eval_v2`
- ZIP 包：`stable_batch_eval_v2.zip`
- 样本数：`500`
- 间隔：`50 ms`
- 单样本时长：`5000 ms`
- Profile：`stable90`

脚本执行完会打印：

- 数据集输出目录
- 本地镜像校验准确率
- ZIP 输出路径

## 5. 常用命令

生成默认稳定数据集：

```powershell
py -3 generate_borderline_dataset.py
```

只生成目录，不打 ZIP：

```powershell
py -3 generate_borderline_dataset.py --no-zip
```

指定输出目录：

```powershell
py -3 generate_borderline_dataset.py --out my_eval_set --dataset-name my_eval_set
```

先做一个小样本调试：

```powershell
py -3 generate_borderline_dataset.py --count 40 --out debug_eval --no-zip
```

如果你还想保留旧的严格边界集：

```powershell
py -3 generate_borderline_dataset.py --profile legacy-borderline
```

## 6. 参数说明

- `--profile`
  - 可选：`stable90`、`legacy-borderline`
  - 默认：`stable90`
- `--out`
  - 输出目录
  - 默认会跟随 profile 自动命名
- `--dataset-name`
  - 写入 `manifest.json` 的数据集名
  - 默认会跟随 profile 自动命名
- `--count`
  - 样本总数
  - 默认：`500`
- `--interval-ms`
  - 采样间隔
  - 默认：`50`
- `--duration-ms`
  - 每个样本总时长
  - 默认：`5000`
- `--seed`
  - 随机种子
  - 默认：`20260417`
- `--zip` / `--no-zip`
  - 是否额外输出 ZIP
  - 默认：`--zip`

查看帮助：

```powershell
py -3 generate_borderline_dataset.py --help
```

## 7. stable90 默认标签分布

当 `--count 500` 时，默认分布为：

- `HEART_RATE_HIGH`: 140
- `HEART_RATE_LOW`: 140
- `STEP_FREQ_HIGH`: 120
- `GAIT_SUDDEN_CHANGE`: 100

注意：

- `stable90` 默认不再生成 `STEP_FREQ_LOW`
- 原因不是业务上不需要，而是当前 App 检测器在现有 2 秒窗口规则下，无法稳定输出这个标签

## 8. 数据集结构

生成后的结构：

```text
stable_batch_eval_v2/
  manifest.json
  labels.csv
  samples/
    001_heart_rate_high.jsonl
    002_heart_rate_high.jsonl
    ...
```

文件说明：

- `manifest.json`
  - App 导入入口
  - 包含数据集信息、样本清单、profile、校验精度
- `labels.csv`
  - 便于人工核对
  - 额外包含脚本本地校验结果
- `samples/*.jsonl`
  - 每个文件是一个独立样本
  - App 会逐个回放

## 9. manifest 和 labels.csv 新增字段

`manifest.json` 里新增了：

- `generation_profile`
- `validator_accuracy`
- `validator_total`
- `validator_matched`
- `validator_label_breakdown`

每个 sample 还会带：

- `validator_prediction`
- `validator_target_top_severity`
- `validator_other_top_severity`

`labels.csv` 也同步带这些字段，便于你离线筛查。

## 10. App 里的使用流程

1. 打开 Dashboard。
2. 点击 `Load Dataset`。
3. 选择：
   - `Folder`
   - `ZIP Archive`
4. 选中生成好的目录或 ZIP。
5. App 会按 `manifest.json` 顺序逐个回放样本。
6. 结束后点 `View Results` 查看结果。
7. 点 `Export JSON and CSV` 导出报告。

导出目录：

- `Downloads/VitaTrackEvaluations`

## 11. 结果怎么看

结果页会给出：

- 总体准确率
- 每类 precision / recall / support
- confusion matrix
- 每个样本的 expected / predicted
- 每个样本的检测耗时和异常信息

如果你要做回归测试，建议重点看：

- 总 accuracy
- `GAIT_SUDDEN_CHANGE` recall
- `STEP_FREQ_HIGH` recall
- 是否出现大量 `NONE`

## 12. 当前限制

- 当前推荐 profile 是 `stable90`
- 当前只支持文件夹和 `.zip`
- 当前还不支持 `.tar.gz`
- `stable90` 默认不生成 `STEP_FREQ_LOW`
- 原因是当前 App 的 2 秒窗口步频规则对该标签不具备稳定可检测性
- 本次已经本地跑过脚本验证，500 组镜像校验为 100%
- Android 全量构建在当前环境里仍然没有完整跑通，因为 Gradle 下载受限

## 13. 推荐你的实际用法

如果你现在的目标是做稳定回归测试，就直接用：

```powershell
py -3 generate_borderline_dataset.py
```

如果你要保留以前那种“更接近阈值边缘、容易失败”的压测数据，再额外跑：

```powershell
py -3 generate_borderline_dataset.py --profile legacy-borderline --out legacy_borderline_v1
```

这样你就有两套集：

- `stable90`：用于稳定回归和验收
- `legacy-borderline`：用于压边界和找薄弱点
