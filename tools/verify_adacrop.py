"""端到端核查：PyTorch 与 ONNX 在同一张真实图上是否给出一致的裁剪框。

同时输出参数量 / 体积，用来判断权重有没有丢。
"""

import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "models"))

from common import MobileNetPolicy, torch_load_portable  # noqa: E402
from export_onnx import BBoxWrapper, load_student  # noqa: E402


def main():
    ckpt = HERE / "models" / "student_best.pth"
    onnx_path = HERE / "models" / "adacrop_mnv3.onnx"

    model = load_student(ckpt)
    n_params = sum(p.numel() for p in model.parameters())
    n_used = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"[torch ] 参数总量 {n_params:,} ({n_params * 4 / 1024 / 1024:.2f} MB fp32)")
    print(f"[torch ] 可训练参数 {n_used:,}")

    m = onnx.load(str(onnx_path))
    init_bytes = sum(i.ByteSize() for i in m.graph.initializer)
    print(f"[onnx  ] initializer {len(m.graph.initializer)} 个, 共 {init_bytes / 1024 / 1024:.2f} MB, 文件 {onnx_path.stat().st_size / 1024:.0f} KB")

    # 用真实构图测试图
    img_path = HERE / "test_thirds.png"
    if not img_path.exists():
        raise SystemExit("缺少测试图，先跑 verify_nima.py 生成")
    img = Image.open(img_path).convert("RGB").resize((224, 224))
    x = np.asarray(img, dtype=np.float32) / 255.0            # 与训练一致：只 /255
    xt = torch.from_numpy(x).permute(2, 0, 1).unsqueeze(0)   # HWC -> 1CHW

    with torch.no_grad():
        pt_out = BBoxWrapper(model)(xt).numpy()[0]

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    ort_out = sess.run(None, {"image": xt.numpy()})[0][0]

    print(f"[input ] {img_path.name}")
    print(f"[torch ] cxcywh = {pt_out.round(4).tolist()}")
    print(f"[ort   ] cxcywh = {ort_out.round(4).tolist()}")
    print(f"[diff  ] max={float(np.abs(pt_out - ort_out).max()):.2e}")

    cx, cy, w, h = ort_out
    x1, y1, x2, y2 = (cx - w / 2) * 224, (cy - h / 2) * 224, (cx + w / 2) * 224, (cy + h / 2) * 224
    print(f"[box   ] xyxy(224尺度) = [{x1:.1f}, {y1:.1f}, {x2:.1f}, {y2:.1f}]  占画面 {w * h * 100:.1f}%")

    # 延迟
    inp = np.zeros((1, 3, 224, 224), dtype=np.float32)
    for _ in range(5):
        sess.run(None, {"image": inp})
    import time
    t0 = time.perf_counter()
    n = 50
    for _ in range(n):
        sess.run(None, {"image": inp})
    print(f"[latency] 桌面 CPU 平均 {(time.perf_counter() - t0) / n * 1000:.1f} ms/帧")

    ok = float(np.abs(pt_out - ort_out).max()) < 1e-4
    print("[结论  ]", "PyTorch 与 ONNX 一致，可以进 Android" if ok else "不一致，需排查")


if __name__ == "__main__":
    main()
