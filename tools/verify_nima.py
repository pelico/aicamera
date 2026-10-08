"""验证 NIMA 美学评分 tflite 能在桌面端正确推理，并测单帧延迟。

用法:
    python verify_nima.py --model models/nima_aesthetic_fp16.tflite

注意预处理: 输入 [1,224,224,3] float32，x = img/127.5 - 1  → 值域 [-1,1]。
"""

import argparse
import time
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image, ImageDraw


def make_test_images(size=224):
    """造两张对比图：一张主体居中，一张主体压三分线。"""
    imgs = {}
    # 居中
    im = Image.new("RGB", (size, size), (205, 210, 215))
    d = ImageDraw.Draw(im)
    d.ellipse([size * 0.40, size * 0.40, size * 0.60, size * 0.60], fill=(200, 70, 50))
    imgs["centered"] = im
    # 三分线（左三分之一竖向）
    im2 = Image.new("RGB", (size, size), (205, 210, 215))
    d2 = ImageDraw.Draw(im2)
    d2.rectangle([0, 0, size, size * 0.62], fill=(120, 150, 175))  # 天空占上 2/3
    d2.ellipse([size * 0.28, size * 0.42, size * 0.44, size * 0.58], fill=(200, 70, 50))
    imgs["thirds"] = im2
    return imgs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", type=Path, default=Path(__file__).parent / "models" / "nima_aesthetic_fp16.tflite")
    args = ap.parse_args()

    it = Interpreter(model_path=str(args.model))
    it.allocate_tensors()
    din = it.get_input_details()[0]
    dout = it.get_output_details()[0]
    print(f"[model] {args.model.name}  {args.model.stat().st_size/1024/1024:.2f} MB")
    print(f"[input ] shape={din['shape']} dtype={din['dtype']}")
    print(f"[output] shape={dout['shape']} dtype={dout['dtype']}")

    imgs = make_test_images()
    for name, im in imgs.items():
        im.resize((224, 224)).save(Path(__file__).parent / f"test_{name}.png")
        x = (np.asarray(im.resize((224, 224)), dtype=np.float32) / 127.5 - 1.0)[None]
        it.set_tensor(din["index"], x)
        it.invoke()
        dist = it.get_tensor(dout["index"])[0].astype(np.float64)
        score = float((np.arange(10) + 1) @ dist)
        print(f"[score ] {name:9s} = {score:.3f}   (dist sum={dist.sum():.4f})")

    # 延迟（桌面 CPU，仅供数量级参考，真机需另测）
    x = np.zeros((1, 224, 224, 3), dtype=np.float32)
    for _ in range(5):
        it.set_tensor(din["index"], x)
        it.invoke()
    t0 = time.perf_counter()
    n = 50
    for _ in range(n):
        it.set_tensor(din["index"], x)
        it.invoke()
    dt = (time.perf_counter() - t0) / n * 1000
    print(f"[latency] 桌面 CPU 平均 {dt:.1f} ms/帧 ({n} 次)")


if __name__ == "__main__":
    main()
