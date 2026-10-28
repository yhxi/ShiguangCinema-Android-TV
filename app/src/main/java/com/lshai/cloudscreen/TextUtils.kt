package com.lshai.cloudscreen

/**
 * 片名/简介这类文本的清洗规则。
 *
 * 单独放在这里有两个原因：一是这些规则散落在 Activity 里改起来容易顾此失彼
 * （搜索命中率、历史记录归一化、详情简介显示都依赖同一套规则）；
 * 二是它们都是纯函数，放在这里才能被单元测试覆盖 —— 在 Activity 的私有方法里没法测。
 */

/** 采集站的简介常带 `<p>` / `<br>` / `&nbsp;` 这类标记，直接显示会很脏。 */
internal fun stripHtml(text: String): String =
    text.replace(Regex("<[^>]{0,40}>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

/** 去掉括号注记、年份与标点，得到更可能被采集站命中的片名。 */
internal fun cleanKeyword(keyword: String): String =
    keyword
        .replace(Regex("[（(\\[【][^）)\\]】]*[）)\\]】]"), "")
        .replace(Regex("[·:：!！?？,，.。\\-—_~、|]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** `"海贼王 · 第 1135/1180 集"` → `"海贼王"` */
internal fun seriesNameOf(label: String): String =
    label.substringBefore(" · 第").substringBefore("·第").trim()

/**
 * 剧名归一化：不同采集站返回的标题经常差在空格、全角括号、`·` 分隔符上
 * （「海贼王」/「海贼王 · 更新至 1000 集」），不归一化就白白匹配不上。
 */
internal fun normalizeSeries(series: String): String =
    series.replace(Regex("[\\s·・\\-—_()（）\\[\\]【】:：,，.。]"), "").lowercase()
