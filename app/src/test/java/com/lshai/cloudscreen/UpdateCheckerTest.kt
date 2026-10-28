package com.lshai.cloudscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较的回归测试。
 *
 * 这段逻辑决定「要不要弹更新提示」。判错的表现有两种：
 * 把新版本当旧的（永远收不到更新）、把旧版本当新的（每次打开都弹一遍）。
 * 它还是纯字符串处理，最适合用测试固定住。
 */
class UpdateCheckerTest {

    @Test
    fun `更高的版本才算新版本`() {
        assertTrue(UpdateChecker.isNewer("v1.1.4", "1.1.3"))
        assertTrue(UpdateChecker.isNewer("1.1.4", "1.1.3"))
        assertFalse(UpdateChecker.isNewer("v1.1.3", "1.1.3"))
        assertFalse(UpdateChecker.isNewer("v1.1.2", "1.1.3"))
    }

    @Test
    fun `按数字比较而不是按字符串`() {
        // 字符串比较会认为 "1.10.0" < "1.9.0"
        assertTrue(UpdateChecker.isNewer("v1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("v1.9.0", "1.10.0"))
    }

    @Test
    fun `段数不同时按缺失位补零`() {
        assertTrue(UpdateChecker.isNewer("v1.2", "1.1.9"))
        assertFalse(UpdateChecker.isNewer("v1.1", "1.1.0"))
    }

    @Test
    fun `预发布后缀不参与比较`() {
        assertTrue(UpdateChecker.isNewer("v1.1.4-beta", "1.1.3"))
        assertFalse(UpdateChecker.isNewer("v1.1.3-rc1", "1.1.3"))
    }

    @Test
    fun `空 tag 一律不算新版本`() {
        assertFalse(UpdateChecker.isNewer(null, "1.1.3"))
        assertFalse(UpdateChecker.isNewer("", "1.1.3"))
        assertFalse(UpdateChecker.isNewer("   ", "1.1.3"))
    }
}
