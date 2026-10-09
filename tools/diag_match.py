#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
诊断脚本：把 PoseMatcher 的打分逻辑在 Python 里复刻一遍，
统计「365 个 Places365 标签里，有多少能拿到真正有依据的推荐」。

不参与 APK 构建，只用来回答一个问题：
    推荐结果到底是匹配出来的，还是排序稳定把库里前几条顶上来的？

用法：
    python tools/diag_match.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TPL = os.path.join(ROOT, "app/src/main/java/com/pelico/aicamera/engine/PoseTemplate.kt")
CAT = os.path.join(ROOT, "app/src/main/assets/categories_places365.txt")

TAG_HIT, SEGMENT_HIT, GROUP_HIT = 4.0, 2.0, 1.5
TIME_BONUS, LIGHT_BONUS, SPEC_BONUS = 1.2, 1.0, 2.0


def load_templates():
    src = open(TPL, encoding="utf-8").read()
    # 按顶层 "        PoseTemplate(" 切块，避免漏掉文件末尾几条
    parts = src.split("        PoseTemplate(")
    blocks = []
    for p in parts[1:]:
        end = p.find("\n        )")
        blocks.append(p[: end if end > 0 else len(p)])
    out = []
    for b in blocks:
        m_id = re.search(r'id\s*=\s*"([^"]+)"', b)
        m_name = re.search(r'name\s*=\s*"([^"]+)"', b)
        m_group = re.search(r"group\s*=\s*SceneGroup\.(\w+)", b)
        if not (m_id and m_group):
            continue
        m_tags = re.search(r"tags\s*=\s*listOf\((.*?)\)", b, re.S)
        tags = re.findall(r'"([^"]+)"', m_tags.group(1)) if m_tags else []
        out.append(
            dict(
                id=m_id.group(1),
                name=m_name.group(1) if m_name else m_id.group(1),
                group=m_group.group(1),
                tags=tags,
            )
        )
    return out


# ---- groupOf：与 SceneLabels.kt 保持一致（只保留键，够用了） ----
GROUP_RULES = [
    ("STREET", ["parking"]),
    ("WATER", ["beach", "ocean", "coast", "lagoon", "lake/", "river", "waterfall", "pier",
               "boardwalk", "harbor", "berth", "wave", "islet", "swimming_hole", "pond",
               "creek", "canal", "fishpond", "hot_spring", "water_park", "swimming_pool",
               "dam", "marsh", "swamp", "moat", "underwater"]),
    ("SNOW", ["snowfield", "ski_", "ice_floe", "iceberg", "glacier", "igloo",
              "ice_skating", "mountain_snowy", "tundra"]),
    ("MOUNTAIN", ["mountain", "valley", "cliff", "canyon", "butte", "badlands",
                  "volcano", "rock_arch", "crevasse"]),
    ("FOREST", ["forest", "bamboo", "rainforest", "tree_farm", "tree_house",
                "orchard", "grove"]),
    ("FIELD", ["field", "wheat", "corn_", "hayfield", "vineyard", "rice_paddy",
               "pasture", "lawn", "meadow", "farm", "garden", "greenhouse",
               "nursery", "park", "picnic_area"]),
    ("CAMPUS", ["campus", "schoolhouse", "classroom", "playground", "athletic_field",
                "stadium", "gymnasium", "basketball", "soccer_field", "football_field",
                "baseball_field", "volleyball_court", "golf_course", "racecourse",
                "raceway", "martial_arts"]),
    ("RIDE", ["car_interior", "train_interior", "bus_interior", "subway_station",
              "train_station", "airplane_cabin", "bus_station", "cockpit"]),
    ("ARCH", ["temple", "palace", "pagoda", "castle", "church", "mosque", "synagogue",
              "courtyard", "/arch", "archaelogical", "ruin", "kasbah", "medina",
              "village", "staircase", "alcove", "pavilion", "gazebo", "mausoleum",
              "catacomb", "aqueduct", "lighthouse", "tower", "windmill", "monument",
              "excavation", "amphitheater", "fountain"]),
    ("STREET", ["street", "alley", "plaza", "downtown", "crosswalk", "highway",
                "driveway", "promenade", "residential_neighborhood", "market/outdoor",
                "bazaar/outdoor", "shopfront", "building_facade", "doorway/outdoor",
                "slum", "industrial_area", "construction_site", "junkyard", "runway",
                "airfield", "hangar", "bridge", "viaduct"]),
]
SHOP_KEYS = ["coffee_shop", "cafeteria", "restaurant", "bakery", "ice_cream",
             "bookstore", "library", "bar", "pub/", "food_court", "pizzeria", "sushi",
             "delicatessen", "supermarket", "market/indoor", "bazaar/indoor", "florist",
             "gift_shop", "clothing_store", "shopping_mall", "department_store",
             "toyshop", "shoe_shop", "jewelry_shop", "hardware_store", "drugstore",
             "candy_store", "museum", "art_gallery", "science_museum",
             "natural_history", "office", "hotel", "inn/", "youth_hostel", "bedroom",
             "kitchen", "living_room", "dining", "lobby", "corridor", "elevator",
             "hospital", "mansion", "ballroom", "banquet_hall", "conference",
             "auditorium", "lecture_room", "stage/", "movie_theater", "bowling",
             "arcade", "sauna", "jacuzzi", "closet", "attic", "basement",
             "garage/indoor", "laundromat", "pharmacy", "pet_shop", "butchers",
             "general_store", "flea_market", "ticket_booth", "waiting_room",
             "reception", "beauty_salon", "bank_vault", "archive", "throne_room",
             "studio", "amusement_park", "discotheque", "carrousel", "ball_pit",
             "cabin/", "chalet", "house", "apartment_building", "porch", "patio",
             "roof_garden", "balcony", "yard", "campsite", "shed"]


def group_of(label: str) -> str:
    l = label.lower()
    if l.startswith("underwater"):
        return "WATER"
    if "parking" in l:
        return "STREET"
    for g, keys in GROUP_RULES:
        if any(k in l for k in keys):
            return g
    if any(k in l for k in SHOP_KEYS):
        return "SHOP"
    return "GENERAL"


def rank(preds, templates, boost=(), pool=None, limit=3):
    pool = pool or templates
    prob = dict(preds)
    scored = []
    for t in pool:
        best, why = 0.0, None
        for label, p in preds:
            if label in t["tags"]:
                hit = TAG_HIT * p
            elif any(seg in t["tags"] for seg in label.split("/")):
                hit = SEGMENT_HIT * p
            else:
                hit = 0.0
            if hit > best:
                best, why = hit, label
        group = max(
            [GROUP_HIT * p for label, p in preds if group_of(label) == t["group"]],
            default=0.0,
        )
        score = best + group
        src = "tag" if best > 0 else ("group" if group > 0 else "none")
        if t["id"] in boost:
            score += SPEC_BONUS
        scored.append((score, src, t, why))
    scored.sort(key=lambda x: -x[0])

    picked, gc = [], {}
    for item in scored:
        g = item[2]["group"]
        if gc.get(g, 0) >= 2:
            continue
        picked.append(item)
        gc[g] = gc.get(g, 0) + 1
        if len(picked) >= limit:
            break
    for item in scored:
        if any(p[2]["id"] == item[2]["id"] for p in picked):
            continue
        picked.append(item)
        if len(picked) >= limit:
            break
    return picked[:limit]


def main():
    templates = load_templates()
    labels = [
        (l.split(" ", 1)[0].split("/", 2)[-1])
        for l in open(CAT, encoding="utf-8").read().splitlines()
        if l.strip()
    ]
    print("模板 %d 条 / 场景标签 %d 个" % (len(templates), len(labels)))

    cover_tag = set()
    cover_group = {}
    for t in templates:
        cover_tag.update(t["tags"])
        cover_group.setdefault(t["group"], 0)
        cover_group[t["group"]] += 1
    print("模板涉及分组：", cover_group)

    stats = {"tag": 0, "group": 0, "none": 0}
    detail = []
    for label in labels:
        picked = rank([(label, 0.40)], templates)
        top = picked[0]
        stats[top[1]] += 1
        if top[1] == "none":
            detail.append((label, group_of(label), top[2]["id"], round(top[0], 2)))

    total = len(labels)
    print()
    print("=== 只给 top-1 场景标签时，首条推荐的依据来源 ===")
    for k in ("tag", "group", "none"):
        print("  %-6s %3d  (%.0f%%)" % (k, stats[k], 100.0 * stats[k] / total))

    print()
    print("=== 完全没依据（score 只来自时段/光线加权的）样本，前 25 个 ===")
    for d in detail[:25]:
        print("   %-28s group=%-8s -> %s (score=%.2f)" % d)
    print("   共 %d 个" % len(detail))

    # 并列：首条推荐有多少个模板同分？同分意味着实际出的是"库里排最前那条"
    tie_hist = {}
    for label in labels:
        scored = []
        for t in templates:
            best = 0.0
            for lb, p in [(label, 0.40)]:
                if lb in t["tags"]:
                    hit = TAG_HIT * p
                elif any(s in t["tags"] for s in lb.split("/")):
                    hit = SEGMENT_HIT * p
                else:
                    hit = 0.0
                best = max(best, hit)
            g = GROUP_HIT * 0.40 if group_of(label) == t["group"] else 0.0
            scored.append(best + g)
        top = max(scored)
        n = sum(1 for s in scored if abs(s - top) < 1e-6)
        tie_hist[n] = tie_hist.get(n, 0) + 1
    print()
    print("=== 首条推荐同分的模板数（同分=实际由库内顺序决定）===")
    for n in sorted(tie_hist):
        print("   并列 %2d 条：%3d 个场景 (%.0f%%)" % (n, tie_hist[n], 100.0 * tie_hist[n] / total))
    arb = sum(v for k, v in tie_hist.items() if k >= 2)
    print("   存在并列（推荐不由场景唯一决定）的场景：%d / %d (%.0f%%)"
          % (arb, total, 100.0 * arb / total))

    # 有多少标签根本没有任何模板 tag 命中
    hit_any = [l for l in labels if any(l in t["tags"] or
               any(s in t["tags"] for s in l.split("/")) for t in templates)]
    print()
    print("能被某个模板 tag 精确/片段命中的标签：%d / %d (%.0f%%)"
          % (len(hit_any), total, 100.0 * len(hit_any) / total))


def check_new():
    """验证新打分：top-1 场景标签能不能真正决定首条推荐"""
    import json
    import random

    aff_path = os.path.join(ROOT, "app/src/main/assets/scene_affinity.json")
    aff = json.load(open(aff_path, encoding="utf-8"))["map"]
    templates = load_templates()
    labels = [
        (l.split(" ", 1)[0].split("/", 2)[-1])
        for l in open(CAT, encoding="utf-8").read().splitlines()
        if l.strip()
    ]
    decay = [1.0, 0.45, 0.25, 0.12, 0.06]
    random.seed(7)
    mism, ties, tot = 0, 0, 0
    for label in labels:
        preds = [(label, 0.45)] + [(random.choice(labels), 0.12) for _ in range(4)]
        scores = {}
        for t in templates:
            best = 0.0
            for i, (lb, p) in enumerate(preds):
                w = p * decay[i]
                ids = aff.get(lb)
                if ids is not None:
                    pos = ids.index(t["id"]) if t["id"] in ids else -1
                    hit = max(6.0 - pos * 1.5, 0.5) * w if pos >= 0 else 0.0
                else:
                    hit = 0.0
                best = max(best, hit)
            scores[t["id"]] = best
        top = max(scores.values())
        n = sum(1 for s in scores.values() if abs(s - top) < 1e-6)
        winner = max(scores, key=lambda k: scores[k])
        want = aff[label][0]
        tot += 1
        if n >= 2:
            ties += 1
        if winner != want:
            mism += 1
    print()
    print("=== 新打分（亲和表 + top-5 衰减）===")
    print("   首条仍存在同分的场景：%d / %d (%.0f%%)" % (ties, tot, 100.0 * ties / tot))
    print("   首条 != 亲和表首选的场景：%d / %d (%.0f%%)" % (mism, tot, 100.0 * mism / tot))


if __name__ == "__main__":
    main()
    check_new()
