"""把 LiveCompose 蒸馏后的 MobileNetV3 学生模型导出为 ONNX，供 Android 端推理。

用法:
    python export_onnx.py --ckpt models/student_best.pth --out models/adacrop_mnv3.onnx

关键点:
  - 训练期预处理只有 T.ToTensor()（即 /255 + HWC->CHW），没有 ImageNet 归一化。
    推理端必须完全一致，否则输出框全错。
  - 只导出 backbone_forward（bbox head）子图：输入 [1,3,224,224]，输出 [1,4] 归一化 cxcywh。
    RL 的 actor 分支用于逐步调框，实时取景场景用不到，不导出以减小体积。
"""

import argparse
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent / "models"))

from common import MobileNetPolicy, torch_load_portable  # noqa: E402

IMG_SIZE = 224


def load_student(ckpt_path: Path) -> MobileNetPolicy:
    ckpt = torch_load_portable(ckpt_path)
    arch = ckpt.get("arch", "mobilenet_v3_small") if isinstance(ckpt, dict) else "mobilenet_v3_small"
    model = MobileNetPolicy(arch=arch)
    state_dict = ckpt.get("model_state_dict", ckpt) if isinstance(ckpt, dict) else ckpt
    missing, unexpected = model.load_state_dict(state_dict, strict=False)
    print(f"[load] arch={arch}  missing={len(missing)} unexpected={len(unexpected)}")
    if unexpected:
        print(f"[load] unexpected keys: {unexpected[:8]}")
    # bbox head 是关键，缺失就不能用
    if any("bbox_head" in k for k in missing):
        raise RuntimeError(f"bbox_head 权重缺失: {[k for k in missing if 'bbox_head' in k]}")
    return model.eval()


class BBoxWrapper(torch.nn.Module):
    """只暴露 backbone_forward，让导出的图尽量小。"""

    def __init__(self, inner: MobileNetPolicy):
        super().__init__()
        self.inner = inner

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.inner.backbone_forward(x)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", type=Path, default=Path(__file__).parent / "models" / "student_best.pth")
    ap.add_argument("--out", type=Path, default=Path(__file__).parent / "models" / "adacrop_mnv3.onnx")
    ap.add_argument("--opset", type=int, default=12)
    ap.add_argument("--quant", action="store_true", help="额外产出动态量化版本")
    args = ap.parse_args()

    model = load_student(args.ckpt)
    wrapper = BBoxWrapper(model)

    dummy = torch.zeros(1, 3, IMG_SIZE, IMG_SIZE, dtype=torch.float32)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            dummy,
            str(args.out),
            input_names=["image"],
            output_names=["box_cxcywh"],
            opset_version=args.opset,
            do_constant_folding=True,
            dynamic_axes={},  # 固定 1x3x224x224，移动端更快
        )

    # torch 2.9 默认把权重拆到 <name>.onnx.data 外部文件。
    # 重新 load+save 会把权重内联回单文件，方便直接放进 apk assets。
    import onnx

    m = onnx.load(str(args.out))
    m.graph.ClearField("value_info")  # 顺手清掉，否则后续量化会撞 shape inference 报错
    onnx.save(m, str(args.out))
    ext = Path(str(args.out) + ".data")
    if ext.exists():
        ext.unlink()

    size_mb = args.out.stat().st_size / 1024 / 1024
    print(f"[export] {args.out}  ({size_mb:.2f} MB, 单文件已内联权重)")

    # ---- PyTorch vs ONNXRuntime 对齐校验 ----
    import onnx
    import onnxruntime as ort

    onnx.checker.check_model(onnx.load(str(args.out)))
    print("[check] onnx 结构校验通过")

    rng = np.random.default_rng(0)
    worst = 0.0
    for i in range(5):
        x = rng.random((1, 3, IMG_SIZE, IMG_SIZE), dtype=np.float32)
        with torch.no_grad():
            ref = wrapper(torch.from_numpy(x)).numpy()
        sess = ort.InferenceSession(str(args.out), providers=["CPUExecutionProvider"])
        got = sess.run(None, {"image": x})[0]
        d = float(np.abs(ref - got).max())
        worst = max(worst, d)
        print(f"[align] sample {i}: pt={ref[0].round(4).tolist()}  ort={got[0].round(4).tolist()}  maxdiff={d:.2e}")

    print(f"[align] 最大误差 {worst:.2e}  ({'OK' if worst < 1e-4 else '偏差过大，需排查'})")

    if args.quant:
        # 实测（桌面 x86，onnxruntime 1.30）：int8 体积 1.27MB vs fp32 4.36MB，
        # 但延迟 54.7ms vs 4.3ms —— 动态量化对 MobileNetV3 的 depthwise 卷积不友好。
        # 精度损失很小（框偏差约 0.004），所以只在极度在意体积时才用。
        from onnxruntime.quantization import QuantType, quantize_dynamic

        q_out = args.out.with_name(args.out.stem + "_int8.onnx")
        quantize_dynamic(str(args.out), str(q_out), weight_type=QuantType.QInt8)
        print(f"[quant] {q_out}  ({q_out.stat().st_size / 1024 / 1024:.2f} MB)")
        print("[quant] 注意: 桌面实测 int8 比 fp32 慢约 12 倍，移动端默认用 fp32 更划算")


if __name__ == "__main__":
    main()
