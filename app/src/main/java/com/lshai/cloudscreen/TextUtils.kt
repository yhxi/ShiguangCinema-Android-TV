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

/** Empty results stay retryable, including after a successful but stale source response. */
internal fun shouldCacheSearchResult(itemCount: Int, failures: Int): Boolean = itemCount > 0


/** Same season only: whitespace, language labels and Chinese/Arabic season numbers may differ. */
internal fun sourceTitleKey(title: String): String {
    val languages = Regex("中文版|英文版|普通话版|国语版|英语版|粤语版|普通话|国语|英语|粤语")
    val season = Regex("第([一二三四五六七八九十两0-9]+)(季|部)")
    return season.replace(normalizeSeries(title.replace(languages, ""))) { m ->
        val text = m.groupValues[1]
        val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val number = text.toIntOrNull() ?: if ('十' in text) {
            val parts = text.split('十')
            (parts[0].firstOrNull()?.let { digits[it] } ?: 1) * 10 + (parts[1].firstOrNull()?.let { digits[it] } ?: 0)
        } else digits[text.first()] ?: return@replace m.value
        "第$number${m.groupValues[2]}"
    }
}


/** Search the series broadly, then filter with sourceTitleKey to keep the original season. */
internal fun sourceSearchKeyword(title: String): String =
    sourceTitleKey(title).replace(Regex("第[0-9]+(季|部).*$"), "")
