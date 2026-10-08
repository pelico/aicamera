#!/usr/bin/env python3
"""下载端侧模型到 app/src/main/assets。

已存在的文件会跳过，重复执行安全。
默认走 hf-mirror.com（国内可达），可用 --source hf 切回 huggingface.co。

用法：
    python tools/fetch_models.py
    python tools/fetch_models.py --source hf
"""
import argparse
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"

MIRROR = "https://hf-mirror.com"
OFFICIAL = "https://huggingface.co"
PLACES_LABELS = (
    "https://raw.githubusercontent.com/CSAILVision/places365/master/"
    "categories_places365.txt"
)

FILES = [
    (
        "places_fp16.tflite",
        "{base}/litert-community/Places365-ResNet18-LiteRT/resolve/main/places_fp16.tflite",
        22_775_088,
        "Places365-ResNet18 场景分类 · 365 类 · Apache-2.0",
    ),
    (
        "nima_aesthetic_fp16.tflite",
        "{base}/litert-community/NIMA-LiteRT/resolve/main/nima_aesthetic_fp16.tflite",
        6_448_632,
        "NIMA 美学评分 · Apache-2.0",
    ),
    (
        "categories_places365.txt",
        PLACES_LABELS,
        6_833,
        "Places365 类别名",
    ),
]


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "aicamera-fetch"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if total:
                pct = done * 100 // total
                print(f"\r   {dest.name}  {pct}%", end="", flush=True)
    print()
    tmp.replace(dest)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source",
        choices=["mirror", "hf"],
        default="mirror",
        help="mirror=hf-mirror.com（国内快），hf=huggingface.co",
    )
    parser.add_argument("--force", action="store_true", help="已存在也重新下载")
    args = parser.parse_args()

    base = MIRROR if args.source == "mirror" else OFFICIAL
    ASSETS.mkdir(parents=True, exist_ok=True)

    for name, url_tpl, expected, desc in FILES:
        dest = ASSETS / name
        url = url_tpl.format(base=base)
        if dest.exists() and not args.force:
            size = dest.stat().st_size
            flag = "OK " if size == expected else "?? size differs"
            print(f"[skip ] {name}  {size / 1024 / 1024:.1f} MB  {flag}")
            continue
        print(f"[get  ] {name}  ({desc})")
        try:
            download(url, dest)
        except Exception as exc:
            print(f"[fail ] {name}: {exc}")
            if not dest.exists():
                return 1
            print("        保留已有文件继续")
        print(f"[done ] {name}  {dest.stat().st_size / 1024 / 1024:.1f} MB")

    print("\n全部就绪：", ASSETS)
    return 0


if __name__ == "__main__":
    sys.exit(main())
