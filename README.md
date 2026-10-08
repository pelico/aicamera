# AiCamera · 场景驱动的拍照姿势助手

端侧推理，不联网、不上传任何图片。

## 这个 App 解决什么

不是"让 AI 猜一个构图框让你去追"，而是：

> 识别你眼前的场景 → 告诉你人该站哪、朝哪、相机该举多高 → 你照着摆，然后按快门。

不判断人的姿势，也就不存在姿态估计不稳的问题。输出是确定性的几何建议，
不会因为模型抖动而乱跳。

## 三个页面

**取景推荐** — 实时取景，每 1.5 秒识别一次场景（场景变化很慢，不需要高帧率）。
画面上叠加：三分参考线、站位虚线框、人形剪影、水平仪。
下方卡片给出当前场景最匹配的姿势：朝向、机位、分步动作、要点提示。
可左右切换 Top 3，对齐后按快门。

**姿势库** — 45 条姿势模板按 12 个场景大类分类浏览，可以手动指定一条，
切回取景页照着摆。

**拍后分析** — 从相册选一张已拍的照片，识别它的场景、给出美学评分，
然后回答"下次在这儿该怎么拍"。

**性能实测** — 在你机器上真跑两个模型，给出实际单帧延迟。

## 技术栈

| 层 | 用什么 |
|---|---|
| 取景 | CameraX（Preview + ImageAnalysis 320×240 抽帧 + ImageCapture） |
| 场景识别 | Places365-ResNet18，LiteRT fp16，365 类，21.7 MB |
| 光线判断 | 无模型，画面统计量（亮度、对比、上下/左右亮度差、高光占比）+ 系统时段 |
| 姿势匹配 | 标签精确命中 4.0×概率 + 片段命中 2.0× + 分组兜底 1.5× + 时段 1.2 + 光线 1.0 |
| 剪影渲染 | Compose Canvas 纯矢量绘制，零图片资源 |
| UI | Jetpack Compose + Material 3 |

## 模型

| 模型 | 来源 | 协议 | 体积 |
|---|---|---|---|
| Places365-ResNet18 | [CSAILVision/places365](https://github.com/CSAILVision/places365) + [litert-community](https://huggingface.co/litert-community/Places365-ResNet18-LiteRT) | CC BY / Apache-2.0 | 21.7 MB |
| NIMA 美学评分 | [litert-community/NIMA-LiteRT](https://huggingface.co/litert-community/NIMA-LiteRT) | Apache-2.0 | 6.2 MB |

两个模型都是 tflite，统一走 TFLite 运行时，没有引入 ONNX Runtime，
因此 APK 比上一版小约 30 MB。

## 几个实现上的坑

**Places365 要做 ImageNet 归一化，Adacrop 只做 /255。** 两个模型的预处理
不一致，混用会让分类结果完全错乱。SceneClassifier 里写死了归一化参数。

**YUV 帧必须按 `imageInfo.rotationDegrees` 旋转后再判断光线。** 不旋转的话
竖屏时"天空在上"这个前提就是反的，逆光判断全部失效。

**Places365 是国外数据集，国内场景会归到近似标签。** 例如古镇会被判成
`courtyard`、油菜花田判成 `field/cultivated`。`SceneLabels.localHint` 给出
针对性的解释，`PoseMatcher` 的分组兜底也保证任何场景都能出建议。

**TFLite Interpreter 不是线程安全的。** 相机分析线程和性能测试页面可能并发
调用，所有模型调用统一用 `modelLock` 串行化。

## 构建

```bash
./gradlew assembleDebug
```

CI 在 `.github/workflows/build-apk.yml`，推 main 即触发，产物是 debug APK。

## 目录

```
app/src/main/java/com/pelico/aicamera/
├── SceneViewModel.kt          场景/光线/姿势的状态中枢
├── engine/
│   ├── SceneClassifier.kt     Places365 tflite 推理
│   ├── SceneLabels.kt         365 类中文名 + 场景分组 + 本土化提示
│   ├── LightingAnalyzer.kt    时段与光线判断（无模型）
│   ├── PoseTemplate.kt        姿势模板数据模型 + 45 条模板
│   ├── PoseMatcher.kt         场景 → 模板的匹配打分
│   ├── AestheticScorer.kt     NIMA 美学评分
│   ├── MotionTracker.kt       陀螺仪 + 水平仪
│   └── DeviceTier.kt          设备档位
├── ui/
│   ├── MainScreen.kt          权限 + 四个 Tab
│   ├── CameraScreen.kt        取景与引导
│   ├── PoseFigure.kt          剪影绘制 + 叠加层
│   ├── PoseCard.kt            姿势卡片
│   ├── PoseLibraryScreen.kt   姿势库浏览
│   ├── AnalyzeScreen.kt       拍后分析
│   └── BenchmarkScreen.kt     性能实测
└── util/
    ├── FrameConverter.kt      YUV → 已旋转 ARGB
    └── ImageSaver.kt          保存到相册
```

## 说明

姿势模板的内容是手工整理的通用摄影经验，不是从任何受版权保护的图库抓取的；
人形剪影是纯矢量绘制，不含第三方图片素材。
