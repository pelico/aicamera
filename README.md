# AiCamera — 端侧 AI 构图助手

给不支持厂商「AI 构图」的手机，做一个实时取景引导 App。全部推理在端侧完成，不联网、不上传照片。

## 它做什么

| 模式 | 说明 |
|---|---|
| **取景引导** | 实时分析取景画面，画出建议构图框，用箭头告诉你「往左移 / 拉近」，对齐后提示可以按快门 |
| **拍后分析** | 从相册选一张已拍的照片，给出建议裁剪区域、美学评分，可一键保存裁剪结果 |
| **性能实测** | 在当前机型上实测两个模型的单帧延迟，用来验证你的手机跑不跑得动 |

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- CameraX（Preview + ImageAnalysis，RGBA_8888 直出）
- ONNX Runtime Mobile 跑构图模型
- TensorFlow Lite 跑美学评分
- 陀螺仪做跨帧平滑，手抖时自动降低响应灵敏度

## 模型

| 模型 | 来源 | 协议 | 体积 |
|---|---|---|---|
| Adacrop（构图裁剪框） | [LiveCompose/Adacrop-MNV3-Distilled](https://huggingface.co/LiveCompose/Adacrop-MNV3-Distilled) | MIT | 4.36 MB |
| NIMA（美学评分） | [litert-community/NIMA-LiteRT](https://huggingface.co/litert-community/NIMA-LiteRT) | Apache-2.0 | 6.15 MB |

Adacrop 是 MobileNetV3-Small 蒸馏版，输出归一化 `(cx, cy, w, h)` 的裁剪框；
NIMA 输出 1–10 的美学评分。桌面 CPU 实测分别约 4.2ms 和 29ms，真机请用 App 内的「性能实测」页自己测。

模型转换与验证脚本见 [`tools/`](tools/README.md)。

## 构建

推送到 `main` 后 GitHub Actions 会自动构建，产物在 Actions 页面的 Artifacts 里（`aicamera-debug`）。

本地构建：

```bash
./gradlew assembleDebug
```

### 配置签名 release（可选）

默认只产出 debug APK。想出签名 release，在仓库 Settings → Secrets 里加四个变量：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 your.keystore` 的输出 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

配好后工作流会自动多产出 `aicamera-release`。

## 已知限制

1. **无法接管系统相机取景器。** 第三方 App 只能用自己的相机界面，所以「取景引导」发生在本 App 内。
2. **画质可能不如系统相机。** CameraX 走的是公开 Camera2 能力，拿不到厂商私有影像算法（夜景、HDR、人像虚化、色彩调校）。如果更在意画质，用「拍后分析」模式：用系统相机拍，回来让 AI 建议裁剪。
3. 实时取景会同时开相机 + 两个模型推理，低端机会发热。App 会按 CPU 核心数和内存自动选抽帧档位（3 / 5 / 10 fps）。

## 目录结构

```
app/src/main/java/com/pelico/aicamera/
├── MainActivity.kt
├── CompositionViewModel.kt       # 节流、跨帧平滑、状态分发
├── engine/
│   ├── CompositionEngine.kt      # Adacrop ONNX 推理
│   ├── AestheticScorer.kt        # NIMA TFLite 推理
│   ├── Guidance.kt               # 裁剪框 → 可执行的移动/变焦建议
│   ├── MotionTracker.kt          # 陀螺仪，判断手是否端稳
│   └── DeviceTier.kt             # 设备档位 → 抽帧间隔
├── ui/
│   ├── MainScreen.kt
│   ├── CameraScreen.kt           # 取景引导 + 叠加层
│   ├── AnalyzeScreen.kt          # 拍后分析
│   └── BenchmarkScreen.kt        # 真机性能实测
└── util/ImageSaver.kt

tools/                            # 模型导出与验证脚本（Python）
app/src/main/assets/              # onnx + tflite 模型
```

## 许可

代码 MIT。模型遵循各自来源协议（Adacrop: MIT，NIMA: Apache-2.0），
其中 NIMA 源自 idealo/image-quality-assessment，构图模型源自 LiveCompose。
