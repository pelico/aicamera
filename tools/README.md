# AI 构图助手 — 模型侧验证记录

目标：给不支持厂商「AI 构图」的手机做一个端侧实时构图引导 App（Android）。
本目录完成的是**第一步：把模型跑通并验证可进 Android**。

## 结论速览

| 模型 | 来源 | 产物 | 体积 | 桌面 CPU 延迟 | 状态 |
|---|---|---|---|---|---|
| Adacrop 构图裁剪 | LiveCompose/Adacrop-MNV3-Distilled（MIT） | `models/adacrop_mnv3.onnx` | 4.36 MB | **4.2 ms/帧** | 已验证 |
| ├ int8 量化（可选） | 同上 | `models/adacrop_mnv3_int8.onnx` | 1.27 MB | 54.7 ms/帧 | 不推荐 |
| NIMA 美学评分 | litert-community/NIMA-LiteRT（Apache-2.0） | `models/nima_aesthetic_fp16.tflite` | 6.15 MB | 29.0 ms/帧 | 已验证 |

两个模型加起来约 10.5 MB，fp32 构图模型单帧 4.2ms —— 端侧实时取景完全够用，
中低端机降到 3–5 fps 抽帧即可，不构成性能瓶颈。

## 模型规格

**Adacrop（构图裁剪框）**
- 输入：`image` `[1, 3, 224, 224]` float32
- 输出：`box_cxcywh` `[1, 4]`，归一化 `(cx, cy, w, h)`，值域 0–1（末尾有 sigmoid）
- 预处理：**只做 `/255`**，HWC→CHW。**没有 ImageNet 归一化**
- PyTorch ↔ ONNXRuntime 最大误差 `5.96e-08`

**NIMA（美学评分）**
- 输入：`[1, 224, 224, 3]` float32，预处理 `x = img / 127.5 - 1`（值域 [-1, 1]）
- 输出：`[1, 10]` 分布，得分 `Σ(i+1)·pᵢ`，范围 1–10
- 方向性自检：三分线构图 **4.718** > 主体居中 **4.597** ✅

## 踩过的坑（复用时务必注意）

1. **训练期没有 ImageNet 归一化。** 源码 `render_full_image()` 只调用了 `T.ToTensor()`。
   如果按 MobileNet 惯例去减均值除方差，输出框会全错。
2. **torch 2.9 导出会把权重拆到 `<name>.onnx.data`。** 直接把 `.onnx` 丢进 apk assets 会加载失败。
   脚本里已做 load+save 内联回单文件，并删掉外部数据文件。
3. **动态量化反而更慢。** int8 体积少 3 MB，但延迟从 4.3ms 涨到 54.7ms（约 12 倍），
   原因是 ONNX Runtime 的 weight-only 量化对 MobileNetV3 的 depthwise 卷积优化很差。
   精度损失其实很小（框偏差 0.0036），所以只在极端在意体积时才考虑。
4. **torchvision 版本必须匹配 torch。** torch 2.9.1 对应 torchvision 0.24.1；
   装错版本会报 `RuntimeError: operator torchvision::nms does not exist`。
5. **权重来源不是 idealo 原仓**（已 deprecated），用 `dr-robert-li` 维护的 fork 或直接取 HF 上的 LiteRT 版。

## 复现步骤

```bash
# 环境：Python 3.13，torch 2.9.1+cpu / torchvision 0.24.1+cpu
pip install numpy pillow onnx onnxruntime onnxscript ai-edge-litert

# 1. 下载权重（国内用 hf-mirror.com，huggingface.co 主站不通）
#    LiveCompose/Adacrop-MNV3-Distilled: student_best.pth + common.py + train_mobilenet_distill.py
#    litert-community/NIMA-LiteRT:       nima_aesthetic_fp16.tflite

# 2. 导出 ONNX（含单文件内联 + 精度对齐校验）
python export_onnx.py --quant      # --quant 可选，默认产物够用

# 3. 验证
python verify_adacrop.py           # PyTorch vs ONNX 一致性 + 延迟
python verify_nima.py              # tflite 推理 + 构图方向性自检
```

## 接下来的工作

模型侧已确认可行，下一步是把它们接进 Android：

- CameraX 取景 + `ImageAnalysis` 抽帧（3–10 fps，按设备档位自适应）
- ONNX Runtime Mobile 跑构图框，LiteRT 跑 NIMA 评分
- 陀螺仪融合做跨帧平滑，Compose 叠加层画引导箭头
- 「App 内直出」+「拍后分析二次裁剪」双模式
- GitHub Actions 构建签名 APK

> 注：第三方 App 无法接管系统相机取景器，且 CameraX 拿不到厂商私有影像算法，
> App 内直出的画质通常会低于系统相机。这是该功能固有的体验落差。
