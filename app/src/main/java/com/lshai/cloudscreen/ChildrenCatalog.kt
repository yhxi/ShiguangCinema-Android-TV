package com.lshai.cloudscreen

/** Curated starting points; age labels are suggestions, not access restrictions. */
internal data class ChildrenSection(val age: String, val title: String, val titles: List<String>)
internal object ChildrenCatalog {
    val sections = listOf(
        ChildrenSection("3–4岁", "启蒙时光", listOf(
            "小猪佩奇", "萌鸡小队", "海底小纵队", "汪汪队立大功", "布鲁伊",
            "瑞奇宝宝", "米奇妙妙屋", "巧虎", "嘟当曼", "阿奇幼幼园",
            "莎拉与乖乖鸭", "巴巴爸爸", "托马斯和他的朋友们", "小熊维尼历险记", "小羊肖恩", "鼠来宝",
        )),
        ChildrenSection("5–6岁", "探索世界", listOf(
            "超级飞侠", "螺丝钉", "大耳朵图图", "新大头儿子和小头爸爸", "数字积木",
            "字母积木", "神奇校车", "变形警车珀利", "消防员山姆", "巴布工程师",
            "熊猫博士", "棉花糖和云朵妈妈", "小马宝莉", "米奇欢乐多", "查理和劳拉",
            "鼹鼠的故事", "贝肯熊",
        )),
        ChildrenSection("7–8岁", "欢乐冒险", listOf(
            "舒克和贝塔", "喜羊羊与灰太狼", "海尔兄弟", "熊出没", "猪猪侠",
            "小鲤鱼历险记", "葫芦兄弟", "黑猫警长", "蓝猫淘气三千问", "大闹天宫",
            "小蝌蚪找妈妈", "哪吒闹海", "宝莲灯", "西游记", "没头脑和不高兴",
            "大耳朵图图之霸王龙在行动", "舒克贝塔五角飞碟",
        )),
    )
}

/** Replace the old default live tile in saved home orders without disturbing other choices. */
internal fun migrateHomeOrder(order: List<String>): List<String> =
    order.map { if (it == "live") "kids" else it }.distinct()
