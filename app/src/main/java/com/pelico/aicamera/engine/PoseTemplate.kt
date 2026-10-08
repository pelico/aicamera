package com.pelico.aicamera.engine

/**
 * 姿势模板的数据模型与模板库。
 *
 * 设计要点：
 * 1. 模板只描述"人在画面里的位置 + 朝向 + 机位 + 动作要点"，不做任何姿态估计，
 *    因此输出永远是确定性的，不会像回归式构图框那样抖动。
 * 2. [PoseTemplate.tags] 使用 Places365 的原始标签（形如 `forest/broadleaf`），
 *    与 SceneClassifier 输出的标签直接比对，保证匹配链路一致。
 * 3. 站位用归一化坐标表达（0..1），与具体分辨率无关。
 */

enum class SceneGroup(val zh: String) {
    WATER("水边 · 海边"),
    MOUNTAIN("山野 · 高处"),
    FOREST("林间 · 树下"),
    FIELD("田野 · 花海"),
    STREET("街头 · 城市"),
    NIGHT("夜景 · 灯光"),
    SHOP("店内 · 咖啡馆"),
    ARCH("建筑 · 古建"),
    SNOW("雪地 · 冰雪"),
    CAMPUS("校园 · 运动"),
    RIDE("车内 · 站台"),
    GENERAL("通用兜底")
}

enum class BodyFacing(val zh: String) {
    FRONT("正面朝向镜头"),
    SIDE_45("侧身 45°"),
    SIDE_90("全侧面"),
    BACK("背对镜头"),
    LOOK_BACK("背身回眸")
}

enum class CameraAngle(val zh: String) {
    LOW("低机位 · 蹲下拍"),
    EYE("平视 · 与眼同高"),
    HIGH("高机位 · 俯拍")
}

/** 人物站位框，全部为画面归一化坐标（0..1，y 轴向下） */
data class Placement(
    val cx: Float,
    val footY: Float,
    val height: Float
)

/**
 * 人形剪影的绘制参数。角度单位为度，正数表示手臂向外抬起。
 * [lean] 为躯干倾斜（-1..1，正数向右倾），[headTurn] 为头部转向（-1..1）。
 */
data class FigurePose(
    val facing: BodyFacing,
    val armNearDeg: Float = 18f,
    val armFarDeg: Float = -12f,
    val legSpread: Float = 0.18f,
    val lean: Float = 0f,
    val headTurn: Float = 0f
)

data class PoseTemplate(
    val id: String,
    val name: String,
    val group: SceneGroup,
    val tags: List<String> = emptyList(),
    val times: Set<TimeOfDay> = emptySet(),
    val lights: Set<LightQuality> = emptySet(),
    val placement: Placement,
    val figure: FigurePose,
    val camera: CameraAngle = CameraAngle.EYE,
    val prop: String? = null,
    val steps: List<String>,
    val note: String
)

object PoseLibrary {

    val ALL: List<PoseTemplate> = listOf(
        // ---------------- 水边 · 海边 ----------------
        PoseTemplate(
            id = "water_walk_away",
            name = "走向海的背影",
            group = SceneGroup.WATER,
            tags = listOf("beach", "ocean", "coast", "wave", "islet"),
            placement = Placement(cx = 0.5f, footY = 0.84f, height = 0.42f),
            figure = FigurePose(facing = BodyFacing.BACK, armNearDeg = 22f, armFarDeg = 14f, legSpread = 0.3f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "背对镜头，朝海水方向走",
                "走到水线附近停下，别踩进浪里",
                "双手自然下垂，一只手轻撩被风吹乱的头发",
                "摄影师蹲低，让海平线落在上方三分线"
            ),
            note = "海平线一定要平，歪 2° 整张就废了。人物压在画面下三分之一。"
        ),
        PoseTemplate(
            id = "water_look_back",
            name = "海边侧身回眸",
            group = SceneGroup.WATER,
            tags = listOf("beach", "ocean", "coast", "lagoon", "beach_house"),
            placement = Placement(cx = 0.34f, footY = 0.86f, height = 0.5f),
            figure = FigurePose(facing = BodyFacing.LOOK_BACK, armNearDeg = 30f, armFarDeg = -18f, legSpread = 0.22f, headTurn = 0.8f),
            steps = listOf(
                "站在画面左三分之一处",
                "背对大海、侧身站好",
                "听到喊声再回头看镜头，别提前摆好",
                "让海风把头发吹乱一点，表情放松"
            ),
            note = "回眸要抓拍，摆出来的回头通常眼神是死的。连拍 5 张挑 1 张。"
        ),
        PoseTemplate(
            id = "water_rock_sit",
            name = "礁石上远眺",
            group = SceneGroup.WATER,
            tags = listOf("cliff", "coast", "ocean", "rock_arch", "islet"),
            placement = Placement(cx = 0.66f, footY = 0.72f, height = 0.4f),
            figure = FigurePose(facing = BodyFacing.SIDE_90, armNearDeg = 12f, armFarDeg = 40f, legSpread = 0.1f, lean = -0.2f),
            camera = CameraAngle.EYE,
            steps = listOf(
                "侧坐在礁石上，一腿曲一腿伸",
                "身体微微后仰，一只手撑在身后",
                "视线看向远处的海平面，不要看镜头",
                "人物放右侧三分之一，左侧留出大片海面"
            ),
            note = "留白比主体更重要。海面占画面六成以上才有呼吸感。"
        ),
        PoseTemplate(
            id = "water_pier_depth",
            name = "栈道纵深背影",
            group = SceneGroup.WATER,
            tags = listOf("pier", "boardwalk", "harbor", "berth", "promenade"),
            placement = Placement(cx = 0.5f, footY = 0.8f, height = 0.36f),
            figure = FigurePose(facing = BodyFacing.BACK, armNearDeg = 16f, armFarDeg = 10f, legSpread = 0.26f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "站在栈道正中，让栏杆线条向远处收拢",
                "摄影师蹲到膝盖高度，把透视拉长",
                "人物往栈道深处走，走到画面上三分之一处",
                "等脚步落在两条木板接缝上再按快门"
            ),
            note = "栈道是最天然的引导线，别浪费。人越小、纵深越强。"
        ),
        PoseTemplate(
            id = "water_lake_side",
            name = "湖边侧坐垂柳",
            group = SceneGroup.WATER,
            tags = listOf("lake/natural", "pond", "creek", "river", "canal/natural", "fishpond"),
            placement = Placement(cx = 0.4f, footY = 0.76f, height = 0.38f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 26f, armFarDeg = -14f, legSpread = 0.08f, lean = 0.15f),
            steps = listOf(
                "侧坐在岸边，双腿自然弯曲",
                "上身转向镜头约 45°",
                "一只手撑地，另一只手搭在膝上",
                "下巴微收，看向水面倒影"
            ),
            note = "坐姿重心靠后，肩膀放松，肩膀一紧整张就僵。"
        ),
        PoseTemplate(
            id = "water_open_arms",
            name = "瀑布前张开双臂",
            group = SceneGroup.WATER,
            tags = listOf("waterfall", "river", "swimming_hole", "creek"),
            placement = Placement(cx = 0.5f, footY = 0.82f, height = 0.5f),
            figure = FigurePose(facing = BodyFacing.BACK, armNearDeg = 78f, armFarDeg = 72f, legSpread = 0.2f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "背对镜头站定",
                "双臂向两侧张开到与肩同高",
                "深吸一口气把胸打开，别含胸",
                "摄影师压低机位，让人物高过水流"
            ),
            note = "水流量大的时候快门别太快，1/30 秒能拉出水的丝感。"
        ),

        // ---------------- 山野 · 高处 ----------------
        PoseTemplate(
            id = "mtn_ridge_silhouette",
            name = "山脊线上剪影",
            group = SceneGroup.MOUNTAIN,
            tags = listOf("mountain", "mountain_path", "mountain_snowy", "valley", "tundra"),
            times = setOf(TimeOfDay.GOLDEN, TimeOfDay.DUSK, TimeOfDay.DAWN),
            lights = setOf(LightQuality.BACKLIT),
            placement = Placement(cx = 0.38f, footY = 0.62f, height = 0.3f),
            figure = FigurePose(facing = BodyFacing.SIDE_90, armNearDeg = 34f, armFarDeg = 20f, legSpread = 0.14f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "站在山脊线上，人只占画面三成高",
                "侧身对着太阳，让轮廓被镶上金边",
                "摄影师蹲低，以天空为背景",
                "对着人物亮部点测光，让山体压暗"
            ),
            note = "逆光剪影的关键是欠曝两档，宁可黑也不许过曝。"
        ),
        PoseTemplate(
            id = "mtn_summit_arms",
            name = "山顶俯拍张开",
            group = SceneGroup.MOUNTAIN,
            tags = listOf("mountain", "mountain_snowy", "butte", "volcano", "canyon"),
            placement = Placement(cx = 0.5f, footY = 0.8f, height = 0.46f),
            figure = FigurePose(facing = BodyFacing.FRONT, armNearDeg = 70f, armFarDeg = 66f, legSpread = 0.24f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "站在制高点边缘，注意安全距离",
                "双臂斜向上张开",
                "摄影师蹲到很低，把天空占满上半画面",
                "仰拍让人物显得顶天立地"
            ),
            note = "低机位仰拍是最稳的显高手法，比后期拉腿自然得多。"
        ),
        PoseTemplate(
            id = "mtn_path_lookback",
            name = "山路上回眸",
            group = SceneGroup.MOUNTAIN,
            tags = listOf("mountain_path", "mountain", "valley", "forest_path", "canyon"),
            placement = Placement(cx = 0.62f, footY = 0.8f, height = 0.44f),
            figure = FigurePose(facing = BodyFacing.LOOK_BACK, armNearDeg = 28f, armFarDeg = -20f, legSpread = 0.28f, headTurn = 0.9f),
            steps = listOf(
                "沿小路往画面深处走",
                "走到右侧三分之一处停下回头",
                "一只手可以搭在背包带上",
                "让小路从画面左下角延伸进来"
            ),
            note = "路径是最好的引导线，人放在线的终点上。"
        ),
        PoseTemplate(
            id = "mtn_cliff_sit",
            name = "崖边侧坐",
            group = SceneGroup.MOUNTAIN,
            tags = listOf("cliff", "canyon", "butte", "badlands", "rock_arch", "valley"),
            placement = Placement(cx = 0.34f, footY = 0.7f, height = 0.36f),
            figure = FigurePose(facing = BodyFacing.SIDE_90, armNearDeg = 16f, armFarDeg = -24f, legSpread = 0.12f, lean = -0.18f),
            steps = listOf(
                "侧坐在崖边，双腿悬空或一腿收起",
                "身体略向后靠，一手撑地",
                "视线看向远方，不看镜头",
                "人物压在左三分之一，右侧留空"
            ),
            note = "安全第一，任何需要冒险才能拍到的位置都不值得。"
        ),

        // ---------------- 林间 · 树下 ----------------
        PoseTemplate(
            id = "forest_light_spot",
            name = "林间光斑侧身",
            group = SceneGroup.FOREST,
            tags = listOf("forest/broadleaf", "forest_path", "rainforest", "forest_road", "tree_farm"),
            times = setOf(TimeOfDay.MORNING, TimeOfDay.AFTERNOON, TimeOfDay.GOLDEN),
            lights = setOf(LightQuality.SIDE_LIT, LightQuality.BACKLIT),
            placement = Placement(cx = 0.42f, footY = 0.82f, height = 0.52f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 32f, armFarDeg = -16f, legSpread = 0.2f, lean = 0.12f),
            steps = listOf(
                "找一束透过树叶打在地上的光",
                "让人站进光斑里，其余部分留在阴影中",
                "侧身 45°，肩膀朝光",
                "对着人脸亮部测光"
            ),
            note = "林子里光比很大，宁可让背景死黑，也别把人脸拍过曝。"
        ),
        PoseTemplate(
            id = "forest_bamboo_depth",
            name = "竹林纵深背影",
            group = SceneGroup.FOREST,
            tags = listOf("bamboo_forest", "forest_path", "forest_road", "tree_farm"),
            placement = Placement(cx = 0.5f, footY = 0.78f, height = 0.4f),
            figure = FigurePose(facing = BodyFacing.BACK, armNearDeg = 14f, armFarDeg = 8f, legSpread = 0.24f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "站在竹林小道正中",
                "相机贴着两侧的竹子，用它们做前景框",
                "人物往深处走，走到画面中上部",
                "等光线从竹叶缝隙落下来再拍"
            ),
            note = "用两侧竹竿当前景，画面立刻有层次。"
        ),
        PoseTemplate(
            id = "forest_path_lookback",
            name = "林间小道回眸",
            group = SceneGroup.FOREST,
            tags = listOf("forest_path", "forest_road", "forest/broadleaf", "rainforest"),
            placement = Placement(cx = 0.6f, footY = 0.8f, height = 0.48f),
            figure = FigurePose(facing = BodyFacing.LOOK_BACK, armNearDeg = 26f, armFarDeg = -18f, legSpread = 0.26f, headTurn = 0.85f),
            steps = listOf(
                "沿小道往里走",
                "回头时肩膀先转、头后转",
                "一只手拨开旁边的树叶",
                "摄影师退开几步，把小路完整收进画面"
            ),
            note = "肩膀先转头后转，这个时间差就是回眸好看的原因。"
        ),
        PoseTemplate(
            id = "forest_tree_lean",
            name = "树干旁半倚",
            group = SceneGroup.FOREST,
            tags = listOf("forest/broadleaf", "tree_farm", "orchard", "park", "tree_house"),
            placement = Placement(cx = 0.36f, footY = 0.84f, height = 0.5f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 52f, armFarDeg = -10f, legSpread = 0.16f, lean = 0.28f),
            steps = listOf(
                "侧身靠在树干上，重心放一条腿",
                "靠树那侧的手臂抬起扶住树干",
                "另一只手自然下垂或插兜",
                "下巴微收，眼神看向镜头偏下方"
            ),
            note = "靠着东西人就放松了，比空手站着自然得多。"
        ),

        // ---------------- 田野 · 花海 ----------------
        PoseTemplate(
            id = "field_smell_flower",
            name = "花海低头闻花",
            group = SceneGroup.FIELD,
            tags = listOf("field/wild", "orchard", "vineyard", "botanical_garden", "topiary_garden", "vegetable_garden"),
            placement = Placement(cx = 0.4f, footY = 0.8f, height = 0.48f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 58f, armFarDeg = -12f, legSpread = 0.18f, lean = 0.2f),
            steps = listOf(
                "侧身站在花丛里，别踩到花",
                "低头凑近一朵花，鼻尖离花一拳",
                "一只手轻轻捏住花茎",
                "摄影师平视，让花海填满下半画面"
            ),
            note = "低头能藏双下巴，也能让眼神柔和，是性价比最高的动作。"
        ),
        PoseTemplate(
            id = "field_wheat_arms",
            name = "麦田张开双臂",
            group = SceneGroup.FIELD,
            tags = listOf("wheat_field", "corn_field", "hayfield", "field/cultivated", "field/wild"),
            times = setOf(TimeOfDay.GOLDEN, TimeOfDay.DUSK, TimeOfDay.AFTERNOON),
            placement = Placement(cx = 0.5f, footY = 0.84f, height = 0.46f),
            figure = FigurePose(facing = BodyFacing.FRONT, armNearDeg = 74f, armFarDeg = 70f, legSpread = 0.22f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "站在田埂上，别踩进作物",
                "双臂水平张开",
                "摄影师蹲低，让作物挡住部分身体",
                "逆着太阳拍，麦穗会发光"
            ),
            note = "用作物做前景遮挡，人半藏在里面比完全露出来更有味道。"
        ),
        PoseTemplate(
            id = "field_paddy_back",
            name = "田埂上背影",
            group = SceneGroup.FIELD,
            tags = listOf("rice_paddy", "field/cultivated", "vegetable_garden", "farm", "field_road"),
            placement = Placement(cx = 0.44f, footY = 0.78f, height = 0.4f),
            figure = FigurePose(facing = BodyFacing.BACK, armNearDeg = 20f, armFarDeg = 12f, legSpread = 0.3f),
            camera = CameraAngle.LOW,
            prop = "草帽",
            steps = listOf(
                "沿田埂往画面深处走",
                "戴顶草帽，帽檐压低一点",
                "一只手扶帽檐，走两步停一下",
                "让田埂的线条从近处延伸到人物脚下"
            ),
            note = "草帽是最便宜好用的道具，能补光影也能解决手不知道放哪的问题。"
        ),
        PoseTemplate(
            id = "field_grass_lie",
            name = "草地野餐侧躺",
            group = SceneGroup.FIELD,
            tags = listOf("lawn", "picnic_area", "pasture", "park", "meadow"),
            placement = Placement(cx = 0.5f, footY = 0.72f, height = 0.26f),
            figure = FigurePose(facing = BodyFacing.SIDE_90, armNearDeg = 46f, armFarDeg = 18f, legSpread = 0.34f, lean = 0.5f),
            camera = CameraAngle.HIGH,
            prop = "野餐布 / 书本",
            steps = listOf(
                "铺一块野餐布，侧躺用手肘撑地",
                "上面那条腿弯曲，下面那条伸直",
                "摄影师站高一点俯拍",
                "布的颜色选纯色，别抢人物"
            ),
            note = "俯拍时相机要正对人物脸部平面，斜着拍五官会变形。"
        ),

        // ---------------- 街头 · 城市 ----------------
        PoseTemplate(
            id = "street_wall_lean",
            name = "街头靠墙站",
            group = SceneGroup.STREET,
            tags = listOf("street", "alley", "building_facade", "doorway/outdoor", "shopfront", "residential_neighborhood"),
            placement = Placement(cx = 0.36f, footY = 0.86f, height = 0.54f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 20f, armFarDeg = -14f, legSpread = 0.3f, lean = 0.22f),
            steps = listOf(
                "找一面干净或有质感的墙",
                "侧身靠上去，重心放后腿",
                "前腿伸直脚尖点地，后腿弯",
                "一只手插兜，另一只手自然垂下"
            ),
            note = "一腿直一腿弯，人立刻不僵。这是街拍最基础的一招。"
        ),
        PoseTemplate(
            id = "street_crosswalk_walk",
            name = "斑马线行走抓拍",
            group = SceneGroup.STREET,
            tags = listOf("crosswalk", "street", "highway", "plaza", "downtown"),
            placement = Placement(cx = 0.5f, footY = 0.82f, height = 0.5f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 34f, armFarDeg = -28f, legSpread = 0.44f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "真的走起来，别原地摆",
                "让靠近镜头的那条腿先迈出",
                "手臂自然摆动，抓摆到最高点的瞬间",
                "摄影师蹲低，用路面做前景"
            ),
            note = "走路必须真走，摆出来的步伐重心是假的。开连拍。"
        ),
        PoseTemplate(
            id = "street_alley_depth",
            name = "巷子纵深回眸",
            group = SceneGroup.STREET,
            tags = listOf("alley", "street", "promenade", "bazaar/outdoor", "market/outdoor", "village"),
            placement = Placement(cx = 0.58f, footY = 0.8f, height = 0.44f),
            figure = FigurePose(facing = BodyFacing.LOOK_BACK, armNearDeg = 24f, armFarDeg = -16f, legSpread = 0.24f, headTurn = 0.8f),
            steps = listOf(
                "站在巷子中段，让两侧墙面收拢",
                "往里走两步再回头",
                "人物放在右侧三分之一",
                "等巷子尽头有人经过再按快门"
            ),
            note = "巷子自带引导线，尽头有第二个人物会让画面有故事。"
        ),
        PoseTemplate(
            id = "street_window_side",
            name = "橱窗前的侧影",
            group = SceneGroup.STREET,
            tags = listOf("shopfront", "clothing_store", "boutique", "market/outdoor", "shopping_mall/indoor"),
            times = setOf(TimeOfDay.DUSK, TimeOfDay.NIGHT, TimeOfDay.AFTERNOON),
            placement = Placement(cx = 0.34f, footY = 0.84f, height = 0.52f),
            figure = FigurePose(facing = BodyFacing.SIDE_90, armNearDeg = 22f, armFarDeg = -12f, legSpread = 0.14f),
            steps = listOf(
                "侧身站在橱窗玻璃前",
                "让玻璃里的倒影和真人重叠",
                "脸部朝窗内的光源",
                "摄影师退远一点，把整块玻璃收进画面"
            ),
            note = "玻璃反光既是光源也是层次，比纯背景高级。"
        ),

        // ---------------- 夜景 · 灯光 ----------------
        PoseTemplate(
            id = "night_neon_profile",
            name = "霓虹下侧脸",
            group = SceneGroup.NIGHT,
            tags = listOf("downtown", "skyscraper", "street", "plaza", "amusement_park"),
            times = setOf(TimeOfDay.NIGHT, TimeOfDay.DUSK),
            lights = setOf(LightQuality.LOW_LIGHT),
            placement = Placement(cx = 0.4f, footY = 0.82f, height = 0.46f),
            figure = FigurePose(facing = BodyFacing.SIDE_90, armNearDeg = 16f, armFarDeg = -10f, legSpread = 0.12f),
            steps = listOf(
                "侧身对着霓虹灯牌",
                "脸转向光源那一侧",
                "手机手动把曝光压低一档",
                "让灯牌在人物后方当背景光源"
            ),
            note = "夜景人脸容易糊，找有灯的地方借光，比开闪光灯好看。"
        ),
        PoseTemplate(
            id = "night_bridge_back",
            name = "桥上夜景背影",
            group = SceneGroup.NIGHT,
            tags = listOf("bridge", "viaduct", "promenade", "harbor", "pier"),
            times = setOf(TimeOfDay.NIGHT, TimeOfDay.DUSK),
            lights = setOf(LightQuality.LOW_LIGHT),
            placement = Placement(cx = 0.5f, footY = 0.8f, height = 0.38f),
            figure = FigurePose(facing = BodyFacing.BACK, armNearDeg = 18f, armFarDeg = 12f, legSpread = 0.2f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "背对镜头靠在桥栏杆上",
                "双臂自然搭在栏杆上",
                "摄影师蹲低，让桥灯串成一条线",
                "曝光压低，让远景灯光变成光斑"
            ),
            note = "夜里快门慢，手一定要靠在栏杆上借稳。"
        ),
        PoseTemplate(
            id = "night_string_closeup",
            name = "灯串下半身特写",
            group = SceneGroup.NIGHT,
            tags = listOf("amusement_park", "beer_garden", "plaza", "patio", "restaurant_patio", "bar"),
            times = setOf(TimeOfDay.NIGHT, TimeOfDay.DUSK),
            lights = setOf(LightQuality.LOW_LIGHT),
            placement = Placement(cx = 0.46f, footY = 0.88f, height = 0.58f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 44f, armFarDeg = -20f, legSpread = 0.16f),
            steps = listOf(
                "站在灯串下方，让光从斜上方落下来",
                "抬头看灯，露出下颌线",
                "一只手举起靠近灯串",
                "只拍到胸口以上，用灯光当背景虚化"
            ),
            note = "夜里拍半身比全身出片率高得多，全身几乎必然糊。"
        ),
        PoseTemplate(
            id = "night_traffic_stand",
            name = "车流旁站立",
            group = SceneGroup.NIGHT,
            tags = listOf("street", "highway", "downtown", "crosswalk", "overpass"),
            times = setOf(TimeOfDay.NIGHT, TimeOfDay.DUSK),
            lights = setOf(LightQuality.LOW_LIGHT),
            placement = Placement(cx = 0.62f, footY = 0.84f, height = 0.48f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 20f, armFarDeg = -16f, legSpread = 0.2f),
            steps = listOf(
                "站在人行道边缘，注意安全",
                "侧身对着车流方向",
                "快门放到 1/4 秒以上拉出光轨",
                "人物必须完全静止，按住别动"
            ),
            note = "光轨要慢门，人物一动就糊。靠墙或蹲稳再拍。"
        ),

        // ---------------- 店内 · 咖啡馆 ----------------
        PoseTemplate(
            id = "shop_window_sit",
            name = "咖啡馆靠窗侧坐",
            group = SceneGroup.SHOP,
            tags = listOf("coffee_shop", "cafeteria", "restaurant_patio", "bow_window/indoor", "restaurant", "diner/outdoor"),
            placement = Placement(cx = 0.38f, footY = 0.78f, height = 0.44f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 50f, armFarDeg = -14f, legSpread = 0.1f, lean = 0.14f),
            prop = "咖啡杯",
            steps = listOf(
                "选靠窗的位置，侧身坐",
                "让窗光从侧面打在脸上",
                "一只手端起杯子到胸前",
                "视线看窗外，别看镜头"
            ),
            note = "窗光是最美的免费柔光箱，一定要侧着用，正对会平。"
        ),
        PoseTemplate(
            id = "shop_bookshelf",
            name = "书架间回眸",
            group = SceneGroup.SHOP,
            tags = listOf("bookstore", "library/indoor", "archive", "natural_history_museum", "museum/indoor"),
            placement = Placement(cx = 0.6f, footY = 0.8f, height = 0.5f),
            figure = FigurePose(facing = BodyFacing.LOOK_BACK, armNearDeg = 40f, armFarDeg = -18f, legSpread = 0.16f, headTurn = 0.85f),
            prop = "一本书",
            steps = listOf(
                "站在书架过道中间",
                "伸手去够上层的一本书",
                "听到喊声回头看镜头",
                "摄影师退到过道尽头，用书架做透视"
            ),
            note = "书架的竖线天然形成纵深，站在过道里就有层次。"
        ),
        PoseTemplate(
            id = "shop_cheek_rest",
            name = "吧台前托腮",
            group = SceneGroup.SHOP,
            tags = listOf("coffee_shop", "bar", "pub/indoor", "ice_cream_parlor", "cafeteria", "food_court"),
            placement = Placement(cx = 0.44f, footY = 0.84f, height = 0.5f),
            figure = FigurePose(facing = BodyFacing.FRONT, armNearDeg = 84f, armFarDeg = -20f, legSpread = 0.1f, lean = 0.16f),
            steps = listOf(
                "坐在吧台前，手肘撑台面",
                "手掌虚托腮，别真的把脸压变形",
                "身体略微前倾，肩膀放松",
                "摄影师平视，台面占据画面下三分之一"
            ),
            note = "托腮的力道要轻，脸被挤变形是新手最常见的翻车点。"
        ),
        PoseTemplate(
            id = "shop_food_overhead",
            name = "桌面俯拍",
            group = SceneGroup.SHOP,
            tags = listOf("bakery/shop", "restaurant", "food_court", "pizzeria", "sushi_bar", "coffee_shop", "delicatessen"),
            placement = Placement(cx = 0.36f, footY = 0.9f, height = 0.34f),
            figure = FigurePose(facing = BodyFacing.FRONT, armNearDeg = 62f, armFarDeg = 56f, legSpread = 0.08f),
            camera = CameraAngle.HIGH,
            prop = "餐点",
            steps = listOf(
                "相机举到桌面正上方 60cm",
                "身体在画面一角，手部入画",
                "手伸向餐点，抓动作中间态",
                "桌面保持干净，只留一两件道具"
            ),
            note = "俯拍桌面要正上方，斜着拍盘子会变成椭圆。"
        ),

        // ---------------- 建筑 · 古建 ----------------
        PoseTemplate(
            id = "arch_gate_center",
            name = "古建门前正中站",
            group = SceneGroup.ARCH,
            tags = listOf("temple/asia", "palace", "pagoda", "courtyard", "church/outdoor", "mosque/outdoor", "castle"),
            placement = Placement(cx = 0.5f, footY = 0.86f, height = 0.44f),
            figure = FigurePose(facing = BodyFacing.FRONT, armNearDeg = 16f, armFarDeg = -12f, legSpread = 0.12f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "站在门洞正中，完全对称构图",
                "摄影师退远，把整个建筑收进画面",
                "人只占画面四成高，突出建筑体量",
                "水平仪必须归零，古建最忌歪"
            ),
            note = "对称构图是古建唯一不会出错的拍法，歪一点就全毁。"
        ),
        PoseTemplate(
            id = "arch_courtyard_walk",
            name = "院落里侧身走",
            group = SceneGroup.ARCH,
            tags = listOf("courtyard", "temple/asia", "kasbah", "medina", "village", "patio", "cloister"),
            placement = Placement(cx = 0.36f, footY = 0.82f, height = 0.46f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 30f, armFarDeg = -26f, legSpread = 0.36f),
            steps = listOf(
                "沿院墙走，让墙面在身后延伸",
                "侧身对着镜头，走两步停一下",
                "一只手可以摸墙或提裙摆",
                "人物放左三分之一，右侧留出院子"
            ),
            note = "国内古镇、四合院、民宿天井大多会被识别成这一类。"
        ),
        PoseTemplate(
            id = "arch_frame_shot",
            name = "门洞框中取景",
            group = SceneGroup.ARCH,
            tags = listOf("arch", "doorway/outdoor", "alcove", "ruin", "window", "pavilion", "gazebo/exterior"),
            placement = Placement(cx = 0.5f, footY = 0.8f, height = 0.4f),
            figure = FigurePose(facing = BodyFacing.FRONT, armNearDeg = 24f, armFarDeg = -16f, legSpread = 0.14f),
            steps = listOf(
                "让人站在门洞或拱门里",
                "摄影师退到门外，用门框当画框",
                "门洞边缘要完整留在画面内",
                "对人物脸部测光，让门框压暗"
            ),
            note = "框中框构图能立刻把杂乱背景挡掉，是最省事的出片方式。"
        ),
        PoseTemplate(
            id = "arch_stairs_lookback",
            name = "台阶上回眸",
            group = SceneGroup.ARCH,
            tags = listOf("staircase", "temple/asia", "palace", "church/outdoor", "entrance_hall", "amphitheater"),
            placement = Placement(cx = 0.5f, footY = 0.72f, height = 0.42f),
            figure = FigurePose(facing = BodyFacing.LOOK_BACK, armNearDeg = 28f, armFarDeg = -22f, legSpread = 0.22f, headTurn = 0.9f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "站在台阶中段，别站最上面",
                "摄影师在下方仰拍",
                "回头看镜头，一手扶栏杆",
                "台阶的斜线会自然把视线引到人物"
            ),
            note = "仰拍台阶能显腿长，也能把建筑的高度拍出来。"
        ),

        // ---------------- 雪地 · 冰雪 ----------------
        PoseTemplate(
            id = "snow_look_up",
            name = "雪地中仰头",
            group = SceneGroup.SNOW,
            tags = listOf("snowfield", "ski_slope", "mountain_snowy", "glacier", "ice_floe", "tundra"),
            lights = setOf(LightQuality.HARSH, LightQuality.FRONT_LIT),
            placement = Placement(cx = 0.42f, footY = 0.84f, height = 0.5f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 66f, armFarDeg = -14f, legSpread = 0.2f, lean = -0.12f),
            steps = listOf(
                "侧身站定，微微仰头",
                "一只手向上举起，像在接雪",
                "穿颜色鲜艳的衣服，和白雪拉开对比",
                "曝光加一档，否则雪会拍成灰色"
            ),
            note = "雪地测光会骗相机，必须手动加曝光补偿，不然雪是脏的。"
        ),
        PoseTemplate(
            id = "snow_footprint_back",
            name = "雪地脚印背影",
            group = SceneGroup.SNOW,
            tags = listOf("snowfield", "ski_resort", "ice_floe", "ski_slope", "snowfield"),
            placement = Placement(cx = 0.5f, footY = 0.78f, height = 0.36f),
            figure = FigurePose(facing = BodyFacing.BACK, armNearDeg = 20f, armFarDeg = 14f, legSpread = 0.3f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "在没被踩过的雪地上走出一行脚印",
                "摄影师跟在后面低机位拍",
                "人物走到画面上三分之一处",
                "脚印要从画面最近处延伸出去"
            ),
            note = "脚印是最强的引导线，也是免费的。先踩好再让人走第二遍。"
        ),

        // ---------------- 校园 · 运动 ----------------
        PoseTemplate(
            id = "campus_bleacher_sit",
            name = "看台侧坐",
            group = SceneGroup.CAMPUS,
            tags = listOf("campus", "athletic_field/outdoor", "playground", "stadium/soccer", "stadium/football", "soccer_field"),
            placement = Placement(cx = 0.36f, footY = 0.76f, height = 0.4f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 40f, armFarDeg = -18f, legSpread = 0.12f, lean = 0.18f),
            prop = "书包 / 矿泉水",
            steps = listOf(
                "侧坐在看台阶梯上",
                "双腿一高一低踩在不同台阶",
                "一只手撑在身后台阶上",
                "书包放在身侧当道具"
            ),
            note = "阶梯的横线能切掉杂乱背景，坐着比站着干净。"
        ),
        PoseTemplate(
            id = "campus_corridor_lookback",
            name = "走廊尽头回眸",
            group = SceneGroup.CAMPUS,
            tags = listOf("corridor", "campus", "schoolhouse", "library/outdoor", "hallway", "entrance_hall"),
            placement = Placement(cx = 0.56f, footY = 0.8f, height = 0.46f),
            figure = FigurePose(facing = BodyFacing.LOOK_BACK, armNearDeg = 26f, armFarDeg = -20f, legSpread = 0.22f, headTurn = 0.85f),
            prop = "书本",
            steps = listOf(
                "站在走廊中段",
                "双手抱书在胸前",
                "回头看镜头，肩膀先转",
                "摄影师退到走廊尽头，让两侧线条收拢"
            ),
            note = "走廊自带对称和纵深，逆光时还能拍出剪影。"
        ),

        // ---------------- 车内 · 站台 ----------------
        PoseTemplate(
            id = "ride_car_profile",
            name = "副驾侧脸",
            group = SceneGroup.RIDE,
            tags = listOf("car_interior", "bus_interior", "train_interior", "airplane_cabin"),
            placement = Placement(cx = 0.4f, footY = 0.82f, height = 0.42f),
            figure = FigurePose(facing = BodyFacing.SIDE_90, armNearDeg = 30f, armFarDeg = -12f, legSpread = 0.08f),
            steps = listOf(
                "侧身坐好，脸转向车窗",
                "让窗外的光打在侧脸上",
                "摄影师在前排回头拍或副驾外的窗外拍",
                "只拍头和肩，座椅别入画"
            ),
            note = "车里空间小，半身特写是唯一现实的选择。"
        ),
        PoseTemplate(
            id = "ride_platform_wait",
            name = "站台等候背影",
            group = SceneGroup.RIDE,
            tags = listOf("subway_station/platform", "train_station/platform", "train_interior", "bus_station/indoor", "platform"),
            placement = Placement(cx = 0.5f, footY = 0.8f, height = 0.4f),
            figure = FigurePose(facing = BodyFacing.BACK, armNearDeg = 18f, armFarDeg = 10f, legSpread = 0.16f),
            prop = "背包",
            steps = listOf(
                "背对镜头站在站台边（注意安全线）",
                "单肩背个包，一手拉包带",
                "摄影师退到站台另一端",
                "等地铁路轨方向有灯光再拍"
            ),
            note = "站台有很强的透视，人物放正中效果最好。"
        ),

        // ---------------- 通用兜底 ----------------
        PoseTemplate(
            id = "gen_thirds_stand",
            name = "通用三分站姿",
            group = SceneGroup.GENERAL,
            placement = Placement(cx = 0.33f, footY = 0.86f, height = 0.56f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 24f, armFarDeg = -18f, legSpread = 0.24f),
            steps = listOf(
                "站在画面左（或右）三分之一线上",
                "侧身 45°，一腿直一腿弯",
                "双手别对称下垂，一只插兜",
                "人物脚底贴着画面下沿留一点边"
            ),
            note = "不知道怎么站的时候就用这个，出错概率最低。"
        ),
        PoseTemplate(
            id = "gen_low_angle_tall",
            name = "通用低机位显高",
            group = SceneGroup.GENERAL,
            placement = Placement(cx = 0.42f, footY = 0.94f, height = 0.62f),
            figure = FigurePose(facing = BodyFacing.FRONT, armNearDeg = 28f, armFarDeg = -22f, legSpread = 0.3f),
            camera = CameraAngle.LOW,
            steps = listOf(
                "摄影师蹲下，相机到膝盖高度",
                "人物脚底贴画面最下沿",
                "前腿往镜头方向伸一点",
                "仰拍让头顶留出两成空间"
            ),
            note = "低机位 + 脚底贴边，是最直接的显高组合。"
        ),
        PoseTemplate(
            id = "gen_backlit_hair",
            name = "通用逆光发丝",
            group = SceneGroup.GENERAL,
            lights = setOf(LightQuality.BACKLIT),
            times = setOf(TimeOfDay.GOLDEN, TimeOfDay.DUSK, TimeOfDay.DAWN, TimeOfDay.MORNING),
            placement = Placement(cx = 0.38f, footY = 0.84f, height = 0.5f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 42f, armFarDeg = -16f, legSpread = 0.2f, lean = 0.1f),
            steps = listOf(
                "让太阳在人物身后偏一侧",
                "侧身站，让光从背后擦过发梢",
                "对人脸点测光，别对天空",
                "让头发散开一点，边缘才会发光"
            ),
            note = "逆光的金边只在毛茸茸的边缘出现，头发扎太紧就没有。"
        ),
        PoseTemplate(
            id = "gen_sit_side",
            name = "通用侧坐",
            group = SceneGroup.GENERAL,
            placement = Placement(cx = 0.4f, footY = 0.78f, height = 0.4f),
            figure = FigurePose(facing = BodyFacing.SIDE_45, armNearDeg = 44f, armFarDeg = -14f, legSpread = 0.12f, lean = 0.2f),
            steps = listOf(
                "找个能坐的地方，侧身坐",
                "两腿错开高度，别并拢",
                "一只手撑地，重心后移",
                "肩膀放松下沉"
            ),
            note = "坐姿比站姿容错高，紧张的时候先让人坐下。"
        )
    )

    fun byGroup(group: SceneGroup): List<PoseTemplate> = ALL.filter { it.group == group }

    val groups: List<SceneGroup> = SceneGroup.entries.toList()
}
