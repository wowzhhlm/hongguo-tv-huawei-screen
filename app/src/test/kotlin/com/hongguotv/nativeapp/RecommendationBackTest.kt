// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationBackTest {
    @Test
    fun focusOnPlaybackScreenConfirmsExitImmediately() {
        // 启动即播后焦点常驻画面：一次返回即弹退出框，不再要求先把焦点移到左栏。
        assertEquals(
            RecommendationBackResult.CONFIRM_EXIT,
            recommendationBackResult(navigationFocused = false, actionsFocused = false),
        )
    }

    @Test
    fun focusOnActionsReturnsToStageFirst() {
        assertEquals(
            RecommendationBackResult.RETURN_TO_STAGE,
            recommendationBackResult(navigationFocused = false, actionsFocused = true),
        )
    }

    @Test
    fun focusOnNavigationIsDelegatedToOuterExit() {
        // 左侧导航有焦点时，goBack 更外层的 hasNavigationFocus 分支已经处理退出。
        assertEquals(
            RecommendationBackResult.DELEGATE,
            recommendationBackResult(navigationFocused = true, actionsFocused = false),
        )
        assertEquals(
            RecommendationBackResult.DELEGATE,
            recommendationBackResult(navigationFocused = true, actionsFocused = true),
        )
    }
}
