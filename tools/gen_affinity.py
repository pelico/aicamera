#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成 assets/scene_affinity.json —— Places365 全部 365 个标签到模板 id 的有序映射。

为什么需要这张表：
    PoseMatcher 原来靠「tag 精确命中 / tag 片段命中 / 分组兜底」三档打分，
    结果是同一个分组里的模板全部同分，最终返回的是 Kotlin 稳定排序下
    「库里排最前面那条」。实测 365 个场景里 74% 的首条推荐存在同分并列。
    这张表把「这个场景该推哪几条」显式写死，同分并列归零。

生成顺序（越靠前优先级越高）：
    1. OVERRIDE   手工指定（高频 / 容易推错的场景）
    2. KEYWORD    关键词规则（把 SHOP 这种塞了 112 个标签的大组拆开）
    3. TAG        模板自身的 tags 命中
    4. GROUP      分组默认顺序
    5. FALLBACK   通用兜底

不参与 APK 构建。改完 PoseTemplate.kt 后重新跑一次：
    python tools/gen_affinity.py
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TPL = os.path.join(ROOT, "app/src/main/java/com/pelico/aicamera/engine/PoseTemplate.kt")
CAT = os.path.join(ROOT, "app/src/main/assets/categories_places365.txt")
OUT = os.path.join(ROOT, "app/src/main/assets/scene_affinity.json")

MAX_PER_LABEL = 4


def load_templates():
    src = open(TPL, encoding="utf-8").read()
    parts = src.split("        PoseTemplate(")
    out = []
    for p in parts[1:]:
        end = p.find("\n        )")
        b = p[: end if end > 0 else len(p)]
        m_id = re.search(r'id\s*=\s*"([^"]+)"', b)
        m_group = re.search(r"group\s*=\s*SceneGroup\.(\w+)", b)
        if not (m_id and m_group):
            continue
        m_tags = re.search(r"tags\s*=\s*listOf\((.*?)\)", b, re.S)
        tags = re.findall(r'"([^"]+)"', m_tags.group(1)) if m_tags else []
        out.append({"id": m_id.group(1), "group": m_group.group(1), "tags": tags})
    return out


def load_labels():
    return [
        l.split(" ", 1)[0].split("/", 2)[-1]
        for l in open(CAT, encoding="utf-8").read().splitlines()
        if l.strip()
    ]


# ---------------------------------------------------------------- 分组默认顺序
# 顺序即「同场景下更想先给哪一条」，不是随意排的：
# 先给最不容易翻车的通用站姿，再给有特定条件的（需要道具 / 需要特定光线）。
GROUP_DEFAULT = {
    "WATER": ["water_walk_away", "water_look_back", "water_lake_side",
              "water_rock_sit", "water_pier_depth", "water_open_arms"],
    "MOUNTAIN": ["mtn_summit_arms", "mtn_ridge_silhouette", "mtn_path_lookback",
                 "mtn_cliff_sit"],
    "FOREST": ["forest_light_spot", "forest_path_lookback", "forest_bamboo_depth",
               "forest_tree_lean"],
    "FIELD": ["field_smell_flower", "field_wheat_arms", "field_grass_lie",
              "field_paddy_back"],
    "STREET": ["street_wall_lean", "street_alley_depth", "street_window_side",
               "street_glass_reflect", "street_crosswalk_walk"],
    "NIGHT": ["night_neon_profile", "night_string_closeup", "street_rooftop_night",
              "night_bridge_back", "night_traffic_stand"],
    "SHOP": ["shop_window_sit", "shop_cheek_rest", "indoor_table_lean",
             "indoor_gallery_wall", "indoor_mall_atrium", "indoor_sofa_sit",
             "indoor_lobby_wide", "shop_bookshelf", "indoor_desk_work",
             "indoor_window_read", "indoor_bed_sit", "indoor_stairs_sit",
             "indoor_aisle_depth", "indoor_supermarket_aisle", "shop_food_overhead"],
    "ARCH": ["arch_gate_center", "arch_frame_shot", "arch_courtyard_walk",
             "arch_stairs_lookback"],
    "SNOW": ["snow_look_up", "snow_footprint_back"],
    "CAMPUS": ["campus_corridor_lookback", "campus_bleacher_sit"],
    "RIDE": ["ride_car_profile", "ride_platform_wait"],
    "GENERAL": ["gen_thirds_stand", "gen_sit_side", "gen_low_angle_tall",
                "gen_backlit_hair"],
}

FALLBACK = GROUP_DEFAULT["GENERAL"]

# ---------------------------------------------------------------- 关键词规则
# 只针对需要「组内再分流」的分组，尤其是 SHOP（112 个标签，原先只有 4 条模板）
KEYWORD_RULES = [
    # --- 办公 / 工作 ---
    (["office", "computer_room", "conference_room", "embassy", "legislative",
      "art_school", "artists_loft", "archive", "mezzanine"], ["indoor_desk_work"]),
    # --- 居家客厅 ---
    (["living_room", "home_theater", "dorm_room", "mansion", "cottage", "chalet",
      "manufactured_home", "apartment_building"], ["indoor_sofa_sit"]),
    # --- 卧室 / 书房 ---
    (["bedroom", "childs_room", "nursery", "bow_window", "study_room",
      "home_office", "bedchamber"], ["indoor_window_read"]),
    # --- 住宿 ---
    (["hotel", "inn/", "cabin/", "guest_house", "youth_hostel"], ["indoor_bed_sit"]),
    # --- 展馆 ---
    (["museum", "art_gallery", "science_museum", "natural_history", "exhibition",
      "atrium/public"], ["indoor_gallery_wall"]),
    # --- 商场中庭 ---
    (["shopping_mall", "department_store", "escalator", "arcade", "bazaar/indoor",
      "amusement", "discotheque", "bowling", "ball_pit", "carrousel"],
     ["indoor_mall_atrium"]),
    # --- 楼梯 ---
    (["staircase", "fire_escape", "entrance_hall"], ["indoor_stairs_sit"]),
    # --- 餐饮 ---
    (["restaurant", "food_court", "pizzeria", "diner/", "cafeteria", "beer_hall",
      "pub/", "sushi", "delicatessen", "bakery", "ice_cream", "kitchen",
      "dining", "banquet_hall"], ["indoor_table_lean"]),
    # --- 大堂 / 大空间 ---
    (["lobby", "reception", "waiting_room", "auditorium", "lecture_room",
      "movie_theater", "stage/", "throne_room", "ballroom", "conference_center"],
     ["indoor_lobby_wide"]),
    # --- 走廊 ---
    (["corridor", "hallway", "tunnel", "basement", "cellar", "cloister",
      "catacomb", "cockpit"], ["indoor_aisle_depth"]),
    # --- 超市货架 ---
    (["supermarket", "market/indoor", "general_store", "flea_market", "florist",
      "gift_shop", "toyshop", "hardware_store", "drugstore", "candy_store",
      "clothing_store", "shoe_shop", "jewelry_shop", "fabric_store",
      "pet_shop", "butchers", "pharmacy", "bookstore", "library"], ["indoor_supermarket_aisle"]),
    # --- 咖啡 / 吧台 ---
    (["coffee_shop"], ["shop_window_sit", "shop_cheek_rest"]),
    (["bar", "pub/indoor", "ice_cream_parlor"], ["shop_cheek_rest"]),
    # --- 车库 / 棚屋 ---
    (["garage/", "shed", "attic", "closet"], ["gen_sit_side"]),
]

# ---------------------------------------------------------------- 手工覆盖
# 规则兜不住、或者规则会推错的，这里写死
OVERRIDE = {
    "bookstore": ["shop_bookshelf", "indoor_window_read", "indoor_supermarket_aisle"],
    "library/indoor": ["shop_bookshelf", "indoor_desk_work", "indoor_window_read"],
    "library/outdoor": ["arch_frame_shot", "gen_thirds_stand"],
    "coffee_shop": ["shop_window_sit", "shop_cheek_rest", "shop_food_overhead"],
    "restaurant": ["indoor_table_lean", "shop_window_sit", "shop_food_overhead"],
    "restaurant_patio": ["indoor_table_lean", "shop_window_sit"],
    "museum/indoor": ["indoor_gallery_wall", "indoor_mall_atrium", "shop_bookshelf"],
    "office": ["indoor_desk_work", "indoor_lobby_wide", "indoor_window_read"],
    "living_room": ["indoor_sofa_sit", "indoor_window_read", "indoor_bed_sit"],
    "bedroom": ["indoor_window_read", "indoor_bed_sit", "indoor_sofa_sit"],
    "hotel/outdoor": ["indoor_lobby_wide", "arch_frame_shot"],
    "kitchen": ["indoor_table_lean", "shop_food_overhead"],
    "dining_room": ["indoor_table_lean", "shop_food_overhead"],
    "airport_terminal": ["indoor_lobby_wide", "indoor_aisle_depth", "indoor_mall_atrium"],
    "subway_station/indoor": ["indoor_aisle_depth", "ride_platform_wait"],
    "train_station/indoor": ["indoor_aisle_depth", "ride_platform_wait"],
    "bus_station/indoor": ["indoor_aisle_depth", "ride_platform_wait"],
    "elevator": ["indoor_aisle_depth", "gen_thirds_stand"],
    "escalator/indoor": ["indoor_stairs_sit", "indoor_mall_atrium"],
    "parking_garage/indoor": ["indoor_aisle_depth", "gen_thirds_stand"],
    "parking_garage/outdoor": ["street_rooftop_night", "street_glass_reflect"],
    "parking_lot": ["street_glass_reflect", "street_rooftop_night", "gen_thirds_stand"],
    "gas_station": ["night_neon_profile", "street_glass_reflect"],
    "hospital": ["indoor_lobby_wide", "gen_sit_side"],
    "supermarket": ["indoor_supermarket_aisle", "indoor_aisle_depth"],
    "shopping_mall/indoor": ["indoor_mall_atrium", "indoor_supermarket_aisle", "indoor_gallery_wall"],
    "church/indoor": ["indoor_gallery_wall", "indoor_aisle_depth", "arch_frame_shot"],
    "temple/asia": ["arch_gate_center", "arch_frame_shot", "arch_courtyard_walk"],
    "courtyard": ["arch_courtyard_walk", "arch_frame_shot"],
    "castle": ["arch_gate_center", "arch_frame_shot"],
    "palace": ["arch_gate_center", "arch_frame_shot"],
    "desert/sand": ["gen_low_angle_tall", "gen_backlit_hair", "field_grass_lie"],
    "desert/vegetation": ["gen_backlit_hair", "gen_low_angle_tall"],
    "desert_road": ["street_alley_depth", "gen_low_angle_tall"],
    "ski_slope": ["snow_look_up", "snow_footprint_back"],
    "ski_resort": ["snow_look_up", "snow_footprint_back"],
    "gymnasium/indoor": ["campus_bleacher_sit", "indoor_lobby_wide"],
    "athletic_field/outdoor": ["campus_corridor_lookback", "field_grass_lie"],
    "playground": ["campus_corridor_lookback", "gen_sit_side"],
    "swimming_pool/outdoor": ["water_lake_side", "water_open_arms"],
    "swimming_pool/indoor": ["water_lake_side", "indoor_lobby_wide"],
    "water_park": ["water_open_arms", "water_lake_side"],
    "amusement_park": ["indoor_mall_atrium", "campus_corridor_lookback"],
    "boat_deck": ["water_look_back", "water_walk_away"],
    "bridge": ["night_bridge_back", "arch_frame_shot", "street_alley_depth"],
    "viaduct": ["street_alley_depth", "night_bridge_back"],
    "tower": ["arch_frame_shot", "gen_low_angle_tall"],
    "skyscraper": ["street_glass_reflect", "gen_low_angle_tall"],
    "building_facade": ["street_glass_reflect", "street_wall_lean"],
    "shopfront": ["street_glass_reflect", "street_window_side"],
    "balcony/exterior": ["street_rooftop_night", "street_window_side"],
    "roof_garden": ["street_rooftop_night", "gen_backlit_hair"],
    "yard": ["gen_backlit_hair", "gen_sit_side"],
    "porch": ["gen_sit_side", "gen_thirds_stand"],
    "patio": ["gen_sit_side", "indoor_table_lean"],
    "campsite": ["gen_backlit_hair", "gen_sit_side"],
    "lawn": ["field_grass_lie", "gen_sit_side"],
    "park": ["forest_light_spot", "field_grass_lie", "gen_backlit_hair"],
    "picnic_area": ["field_grass_lie", "gen_sit_side"],
    "formal_garden": ["arch_courtyard_walk", "field_smell_flower"],
    "japanese_garden": ["arch_frame_shot", "forest_light_spot"],
    "botanical_garden": ["forest_light_spot", "field_smell_flower"],
    "greenhouse/indoor": ["forest_light_spot", "indoor_gallery_wall"],
    "cemetery": ["arch_frame_shot", "gen_backlit_hair"],
    "runway": ["gen_low_angle_tall", "street_alley_depth"],
    "heliport": ["gen_low_angle_tall", "street_alley_depth"],
    "landing_deck": ["gen_low_angle_tall", "street_alley_depth"],
    "hangar/indoor": ["indoor_lobby_wide", "indoor_aisle_depth"],
    "hangar/outdoor": ["street_glass_reflect", "gen_low_angle_tall"],
    "fire_station": ["street_glass_reflect", "gen_thirds_stand"],
    "construction_site": ["street_glass_reflect", "gen_low_angle_tall"],
    "industrial_area": ["street_glass_reflect", "street_alley_depth"],
    "junkyard": ["street_glass_reflect", "gen_sit_side"],
    "landfill": ["gen_thirds_stand"],
    "stadium/football": ["campus_bleacher_sit", "gen_low_angle_tall"],
    "stadium/baseball": ["campus_bleacher_sit", "gen_low_angle_tall"],
    "stadium/soccer": ["campus_bleacher_sit", "gen_low_angle_tall"],
    # --- 上面 51 个掉进通用兜底的场景里，确实还能拍的给个归属，
    #     剩下那些（浴室 / 更衣室 / 机房 / 牢房…）交给 SceneUsability 判为不宜 ---
    "aquarium": ["night_neon_profile", "indoor_gallery_wall", "indoor_aisle_depth"],
    "ice_shelf": ["snow_look_up", "mtn_ridge_silhouette", "snow_footprint_back"],
    "grotto": ["arch_frame_shot", "indoor_aisle_depth"],
    "raft": ["water_lake_side", "water_walk_away"],
    "watering_hole": ["water_lake_side", "field_grass_lie"],
    "railroad_track": ["street_alley_depth", "gen_low_angle_tall"],
    "trench": ["street_alley_depth", "gen_low_angle_tall"],
    "sandbox": ["campus_corridor_lookback", "field_grass_lie"],
    "sky": ["gen_low_angle_tall", "gen_backlit_hair"],
    "hunting_lodge/outdoor": ["gen_backlit_hair", "forest_tree_lean"],
    "motel": ["indoor_bed_sit", "street_glass_reflect"],
    "oilrig": ["gen_low_angle_tall", "street_glass_reflect"],
    "playroom": ["indoor_sofa_sit", "campus_corridor_lookback"],
    "recreation_room": ["indoor_sofa_sit", "indoor_table_lean"],
    "television_room": ["indoor_sofa_sit", "indoor_bed_sit"],
    "pantry": ["indoor_supermarket_aisle", "indoor_table_lean"],
    "phone_booth": ["street_window_side", "night_neon_profile"],
    "booth/indoor": ["indoor_table_lean", "shop_window_sit"],
    "arena/performance": ["indoor_lobby_wide", "indoor_mall_atrium"],
    "arena/hockey": ["campus_bleacher_sit", "indoor_lobby_wide"],
    "arena/rodeo": ["campus_bleacher_sit", "gen_sit_side"],
    "army_base": ["gen_low_angle_tall", "street_alley_depth"],
    "bullring": ["arch_frame_shot", "gen_low_angle_tall"],
    "orchestra_pit": ["indoor_lobby_wide", "indoor_aisle_depth"],
    "nursing_home": ["gen_sit_side", "indoor_sofa_sit"],
}


# ---------------------------------------------------------------- 场景适宜度
# 有些地方根本不该推荐「摆姿势」——在浴室、更衣室、机房里给姿势建议是荒谬的。
# 这两张表由 App 侧的 SceneUsability 读取：
#   poor  明确不宜拍人像，直接说明原因，不推模板
#   weak  能拍但要挑角度，给模板的同时附一句提醒
POOR_KEYS = [
    "bathroom", "shower", "closet", "attic", "basement", "locker_room",
    "dressing_room", "clean_room", "chemistry_lab", "biology_laboratory",
    "physics_laboratory", "engine_room", "assembly_line", "auto_factory",
    "landfill", "jail_cell", "burial_chamber", "catacomb", "kennel/outdoor",
    "corral", "stable", "operating_room", "laundromat", "storage_room",
    "utility_room", "server_room", "repair_shop", "elevator", "hospital",
    "nursing_home", "crematorium", "mine", "slaughterhouse", "sewer",
]
WEAK_KEYS = [
    "parking_garage/indoor", "parking_lot", "industrial_area",
    "construction_site", "gas_station", "loading_dock", "fire_station",
    "army_base", "junkyard", "oilrig", "auto_showroom", "warehouse",
    "quarry", "sawmill", "dump", "landfill",
]


def build():
    templates = load_templates()
    labels = load_labels()
    by_id = {t["id"]: t for t in templates}
    order = [t["id"] for t in templates]  # 同分时按库内顺序，至少是确定的
    group_of = {t["id"]: t["group"] for t in templates}

    for _, lst in GROUP_DEFAULT.items():
        for tid in lst:
            if tid not in by_id:
                raise SystemExit("GROUP_DEFAULT 引用了不存在的模板：%s" % tid)

    def fill(ids, group, limit=MAX_PER_LABEL):
        """把候选补齐到 limit 条，用分组默认顺序补位"""
        out = [i for i in ids if i in by_id]
        for tid in GROUP_DEFAULT.get(group, FALLBACK):
            if len(out) >= limit:
                break
            if tid not in out:
                out.append(tid)
        for tid in FALLBACK:
            if len(out) >= limit:
                break
            if tid not in out:
                out.append(tid)
        return out[:limit]

    result = {}
    source = {}
    for label in labels:
        ll = label.lower()
        picked = None
        why = ""

        if label in OVERRIDE:
            # 覆盖里可能跨组，分组按首条的组算
            first = OVERRIDE[label][0]
            picked = fill(OVERRIDE[label], group_of.get(first, "GENERAL"))
            why = "override"
        else:
            for keys, tids in KEYWORD_RULES:
                if any(k in ll for k in keys):
                    picked = fill(tids, group_of[tids[0]])
                    why = "keyword"
                    break

        if picked is None:
            hits = [t["id"] for t in templates
                    if label in t["tags"]
                    or any(seg in t["tags"] for seg in label.split("/"))]
            if hits:
                picked = fill(hits, group_of[hits[0]])
                why = "tag"
            else:
                g = group_of_label(label)
                picked = fill([], g)
                why = "group:" + g

        result[label] = picked
        source[label] = why

    return result, source, labels


# 要和 Kotlin 侧 SceneLabels.groupOf 保持一致，这里只用来决定兜底分组
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


def group_of_label(label: str) -> str:
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


def main():
    result, source, labels = build()
    poor = sorted(l for l in labels if any(k in l.lower() for k in POOR_KEYS))
    weak = sorted(l for l in labels
                  if l not in poor and any(k in l.lower() for k in WEAK_KEYS))
    payload = {
        "version": 2,
        "note": "Places365 标签 → 模板 id 的有序亲和表。由 tools/gen_affinity.py 生成，"
                "改完 PoseTemplate.kt 后重新生成。App 侧由 SceneAffinity 加载，"
                "查不到时回落到模板自身的 tags 与分组兜底。",
        "fallback": FALLBACK,
        "poor": poor,
        "weak": weak,
        "map": result,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)

    from collections import Counter
    c = Counter(v.split(":")[0] for v in source.values())
    print("已写出 %s" % OUT)
    print("标签数 %d，每标签 %d 条模板" % (len(result), MAX_PER_LABEL))
    print("来源分布：", dict(c))
    print("不宜拍人像 %d 个，需挑角度 %d 个" % (len(poor), len(weak)))
    # 首条推荐去重后覆盖了多少条模板
    firsts = Counter(v[0] for v in result.values())
    print("首条推荐覆盖模板 %d 条：" % len(firsts))
    for k, v in firsts.most_common():
        print("   %-28s %d" % (k, v))
    return 0


if __name__ == "__main__":
    sys.exit(main())
