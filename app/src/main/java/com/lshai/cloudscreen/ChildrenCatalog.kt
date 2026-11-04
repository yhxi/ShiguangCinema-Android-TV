package com.lshai.cloudscreen

/** Curated starting points; age labels are suggestions, not access restrictions. */
internal data class ChildrenSection(val age: String, val title: String, val titles: List<String>)
internal object ChildrenCatalog {
    val sections = listOf(
        ChildrenSection("3–4岁", "启蒙时光", listOf(
            "小猪佩奇", "萌鸡小队", "海底小纵队", "汪汪队立大功", "布鲁伊",
            "瑞奇宝宝", "米奇妙妙屋", "巧虎", "嘟当曼", "阿奇幼幼园",
            "莎拉与乖乖鸭", "巴巴爸爸", "托马斯和他的朋友们", "小熊维尼历险记", "小羊肖恩", "鼠来宝",
            "蓝色小考拉", "天线宝宝", "花园宝宝", "小鸡彩虹", "奇奇与努娜", "超级宝贝JOJO",
            "宝宝巴士启蒙音乐剧", "兔小贝", "碰碰狐", "小伴龙", "爱探险的朵拉", "小公主苏菲亚",
            "睡衣小英雄", "小猫奇奇", "可可小爱", "咕力咕力", "小猪威比",
        )),
        ChildrenSection("5–6岁", "探索世界", listOf(
            "超级飞侠", "螺丝钉", "大耳朵图图", "新大头儿子和小头爸爸", "数字积木",
            "字母积木", "神奇校车", "变形警车珀利", "消防员山姆", "巴布工程师",
            "熊猫博士", "棉花糖和云朵妈妈", "小马宝莉", "米奇欢乐多", "查理和劳拉",
            "鼹鼠的故事", "贝肯熊",
            "百变校巴", "帮帮龙出动", "布鲁可战队", "超级小熊布迷", "小猪佩奇过大年",
            "小马宝莉：新世代", "大头儿子和小头爸爸", "小花仙", "爆笑虫子", "小企鹅波鲁鲁",
            "小熊布迷", "图腾领域", "精灵梦叶罗丽", "大卫贝肯之倒霉特工熊", "熊出没之探险日记",
            "喜羊羊与灰太狼之羊羊运动会", "猪猪侠之竞速小英雄",
        )),
        ChildrenSection("7–8岁", "欢乐冒险", listOf(
            "舒克和贝塔", "喜羊羊与灰太狼", "海尔兄弟", "熊出没", "猪猪侠",
            "小鲤鱼历险记", "葫芦兄弟", "黑猫警长", "蓝猫淘气三千问", "大闹天宫",
            "小蝌蚪找妈妈", "哪吒闹海", "宝莲灯", "西游记", "没头脑和不高兴",
            "大耳朵图图之霸王龙在行动", "舒克贝塔五角飞碟",
            "开心超人联盟", "赛尔号", "虹猫蓝兔七侠传", "围棋少年", "小虎还乡",
            "中华勤学故事", "成语动画廊", "阿凡提的故事", "九色鹿", "雪孩子", "三个和尚",
            "疯狂动物城", "冰雪奇缘", "赛车总动员", "玩具总动员", "怪兽电力公司",
        )),
    )

    /** The nine overview cards load first, followed by every remaining title. */
    fun preloadOrder(): List<String> =
        (sections.flatMap { it.titles.take(3) } + sections.flatMap { it.titles.drop(3) }).distinct()
}

/** Replace the old default live tile in saved home orders without disturbing other choices. */
internal fun migrateHomeOrder(order: List<String>): List<String> =
    order.map { if (it == "live") "kids" else it }.distinct()

/** Keep catalog order when filtering; a cover does not imply a playable source. */
internal fun filterChildrenTitles(titles: List<String>, filter: Int, covered: Set<String>, watched: Set<String>): List<String> =
    when (filter) {
        1 -> titles.filter { it in covered }
        2 -> {
            val normalized = watched.map(::normalizeSeries).toSet()
            titles.filter { normalizeSeries(it) in normalized }
        }
        else -> titles
    }
