#!/usr/bin/env python3
"""离线评测 Places365 的**粗类 top-1**，用来拿 M0 基线。

这份脚本的目的不是得到一个漂亮的数字，而是回答一个问题：
「现在的场景识别是不是已经够用，还需要不要花力气上 CLIP？」
判定的口径见 docs/EVALUATION.md。

关键设计：**分组规则从 Kotlin 源码里读出来**，而不是在 Python 里重写一遍。
重写一遍的结果是两边迟早漂移 —— App 改了标签分组，评测还在用旧规则，
那评测数字就是假的。

用法：
    python tools/eval_scene.py --root evalset
    python tools/eval_scene.py --root evalset --limit 20        # 先跑通流程
    python tools/eval_scene.py --root evalset --json report.json
    python tools/eval_scene.py --root evalset --dry-run          # 不推理，只检查目录
"""
import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MODEL = ROOT / "app" / "src" / "main" / "assets" / "places_fp16.tflite"
DEFAULT_LABELS = ROOT / "app" / "src" / "main" / "assets" / "categories_places365.txt"
SCENE_LABELS_KT = ROOT / "app" / "src" / "main" / "java" / "com" / "pelico" / "aicamera" / "engine" / "SceneLabels.kt"
PERCEPTION_KT = ROOT / "app" / "src" / "main" / "java" / "com" / "pelico" / "aicamera" / "contract" / "Perception.kt"

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}

MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)


# ---------------------------------------------------------------- 分组规则（从 Kotlin 解析）

def load_group_rules() -> list[tuple[list[str], str]]:
    """从 SceneLabels.groupOf 里解析 has(...) -> SceneGroup.X 的顺序表。

    顺序很重要：groupOf 是从上往下第一个命中的分支生效，Python 侧必须同样处理。
    """
    text = SCENE_LABELS_KT.read_text(encoding="utf-8")
    body = text.split("fun groupOf", 1)[-1]
    pattern = re.compile(r"has\s*\((.*?)\)\s*->\s*SceneGroup\.(\w+)", re.S)
    rules = []
    for keys_text, group in pattern.findall(body):
        keys = re.findall(r'"([^"]*)"', keys_text)
        if keys:
            rules.append((keys, group))
    return rules


def load_coarse_rules() -> dict[str, str]:
    """从 Perception.SceneResult.from 里解析 SceneGroup -> SceneCoarse。"""
    text = PERCEPTION_KT.read_text(encoding="utf-8")
    body = text.split("fun from(group: SceneGroup)", 1)[-1]
    body = body.split("}", 1)[0]
    out: dict[str, str] = {}
    for groups_text, coarse in re.findall(r"(SceneGroup(?:\.[\w\s,.]+)?)\s*->\s*SceneCoarse\.(\w+)", body):
        for g in re.findall(r"SceneGroup\.(\w+)", groups_text):
            out[g] = coarse
    return out


class Rules:
    def __init__(self) -> None:
        self.group_rules = load_group_rules()
        self.coarse_rules = load_coarse_rules()

    def group_of(self, label: str) -> str:
        low = label.lower()
        for keys, group in self.group_rules:
            if any(k in low for k in keys):
                return group
        return "GENERAL"

    def coarse_of(self, label: str) -> str:
        return self.coarse_rules.get(self.group_of(label), "GENERIC")


# ---------------------------------------------------------------- 数据与推理

def load_labels(path: Path) -> list[str]:
    """与 SceneLabels.parse 保持一致：去掉 `/x/` 前缀。"""
    labels = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        stem = line.split(" ", 1)[0]
        labels.append(stem[3:] if len(stem) > 3 and stem.startswith("/") else stem)
    return labels


def collect(root: Path) -> list[tuple[Path, str]]:
    """评测集约定：<root>/<预期粗类>/*.jpg。粗类名字用 SceneCoarse 的大写名。"""
    items = []
    for cls_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        for img in sorted(cls_dir.iterdir()):
            if img.suffix.lower() in IMAGE_EXTS:
                items.append((img, cls_dir.name.upper()))
    return items


def preprocess(path: Path, size: int = 224):
    # 延迟导入，--dry-run 时不需要这些依赖
    import numpy as np
    from PIL import Image

    im = Image.open(path).convert("RGB")
    s = min(im.size)
    left = (im.width - s) // 2
    top = (im.height - s) // 2
    im = im.crop((left, top, left + s, top + s)).resize((size, size), Image.BILINEAR)
    arr = np.asarray(im, dtype=np.float32) / 255.0
    arr = (arr - np.asarray(MEAN, dtype=np.float32)) / np.asarray(STD, dtype=np.float32)
    # 与 App 一致：先 center crop 再缩放，最后转 NCHW
    return np.transpose(arr, (2, 0, 1))[None].astype(np.float32)


def make_interpreter(model: Path):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        try:
            from tflite_runtime.interpreter import Interpreter  # type: ignore
        except ImportError:
            from tensorflow.lite.python.interpreter import Interpreter  # type: ignore
    interp = Interpreter(model_path=str(model), num_threads=4)
    interp.allocate_tensors()
    return interp


def softmax(x):
    import numpy as np

    e = np.exp(x - x.max())
    return e / e.sum()


# ---------------------------------------------------------------- 主流程

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="评测集根目录，子目录名 = 期望粗类")
    ap.add_argument("--model", default=str(DEFAULT_MODEL))
    ap.add_argument("--labels", default=str(DEFAULT_LABELS))
    ap.add_argument("--limit", type=int, default=0, help="每类最多取多少张，0 表示不限")
    ap.add_argument("--json", default="", help="把结果写成 JSON")
    ap.add_argument("--dry-run", action="store_true", help="只检查目录结构，不做推理")
    args = ap.parse_args()

    root = Path(args.root)
    if not root.exists():
        print(f"[fail] 找不到评测集目录：{root}")
        print("      目录结构应为 <root>/<粗类>/*.jpg，粗类名见 SceneCoarse（WATER / NATURE / ...）")
        return 1

    items = collect(root)
    if not items:
        print(f"[fail] {root} 里没找到图片")
        return 1

    per_class = Counter(p[1] for p in items)
    print("评测集构成：")
    for cls, n in sorted(per_class.items()):
        print(f"  {cls:<12} {n:>4} 张")
    print(f"  {'合计':<12} {len(items):>4} 张\n")

    if args.limit > 0:
        kept = []
        seen = Counter()
        for path, cls in items:
            if seen[cls] < args.limit:
                kept.append((path, cls))
                seen[cls] += 1
        items = kept
        print(f"每类截断到 {args.limit} 张，实际评测 {len(items)} 张\n")

    try:
        rules = Rules()
        print(f"从 Kotlin 解析到 {len(rules.group_rules)} 条分组规则、"
              f"{len(rules.coarse_rules)} 条粗类映射\n")
    except Exception as exc:
        print(f"[fail] 解析分组规则失败：{exc}")
        return 1

    if args.dry_run:
        print("--dry-run：不做推理，目录结构检查通过")
        return 0

    model, labels_path = Path(args.model), Path(args.labels)
    for need in (model, labels_path):
        if not need.exists():
            print(f"[fail] 缺少文件：{need}")
            print("      先跑 python tools/fetch_models.py")
            return 1

    labels = load_labels(labels_path)
    interp = make_interpreter(model)
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]

    nchw = tuple(inp["shape"]) == (1, 3, 224, 224)
    print(f"模型输入 shape={tuple(inp['shape'])}，按 {'NCHW' if nchw else 'NHWC'} 喂数据\n")

    conf = defaultdict(Counter)
    hits = defaultdict(int)
    totals = defaultdict(int)
    top1_detail = []

    for path, expect in items:
        arr = preprocess(path)
        if not nchw:
            import numpy as np

            arr = np.transpose(arr, (0, 2, 3, 1)).copy()
        interp.set_tensor(inp["index"], arr)
        interp.invoke()
        probs = softmax(interp.get_tensor(out["index"])[0])

        n = min(len(labels), probs.shape[0])
        top = [labels[i] for i in probs[:n].argsort()[::-1][:3]]
        pred_coarse = rules.coarse_of(top[0])
        pred_group = rules.group_of(top[0])

        totals[expect] += 1
        if pred_coarse == expect:
            hits[expect] += 1
        conf[expect][pred_coarse] += 1
        top1_detail.append((path.name, expect, top[0], pred_group, pred_coarse, float(probs.max())))

    print("粗类 top-1：")
    correct = 0
    for cls in sorted(totals):
        h, t = hits[cls], totals[cls]
        correct += h
        acc = h / t if t else 0.0
        bar = "#" * int(acc * 20)
        print(f"  {cls:<12} {h:>3}/{t:<3} {acc * 100:5.1f}%  {bar}")
    total = sum(totals.values())
    overall = correct / total if total else 0.0
    print(f"\n  总体粗类 top-1：{correct}/{total} = {overall * 100:.1f}%\n")

    print("混淆（行=标注，列=预测）：")
    cols = sorted({c for row in conf.values() for c in row})
    print("  " + " " * 12 + "".join(f"{c[:8]:>10}" for c in cols))
    for cls in sorted(conf):
        row = conf[cls]
        cells = "".join(f"{row.get(c, 0):>10}" for c in cols)
        print(f"  {cls:<12}{cells}")

    print("\n判据（见 docs/EVALUATION.md）：")
    if total < 100:
        print(f"  ⚠ 样本只有 {total} 张，先别据此下结论 —— 目标是最少 100~150 张")
    if overall >= 0.80:
        print("  ✅ 粗类基线达标，M3（换 CLIP）可以先不做")
    elif overall >= 0.65:
        print("  ⚠ 处于灰区：先看混淆矩阵集中在哪几类，再决定是补标签还是换模型")
    else:
        print("  ❌ 明显不达标，需要排查：图片是否与真实取景一致 / 预处理是否与 App 一致")

    if args.json:
        report = {
            "overall_coarse_top1": overall,
            "total": total,
            "per_class": {c: hits[c] / totals[c] for c in sorted(totals)},
            "confusion": {k: dict(v) for k, v in conf.items()},
            "details": [
                {"file": f, "expect": e, "top1": t, "group": g, "coarse": c, "prob": p}
                for f, e, t, g, c, p in top1_detail
            ],
        }
        Path(args.json).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n已写出 {args.json}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
