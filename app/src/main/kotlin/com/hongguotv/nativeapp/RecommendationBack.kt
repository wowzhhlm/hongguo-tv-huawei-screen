// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * 推荐页（tab 8）返回键决策。
 *
 * 启动即播后焦点常驻画面（stage），v0.18.0 之前从画面按返回只会把焦点移到左侧
 * 导航，要求再按一次才弹退出框；真机上用户常因此认为「按了没反应、退不出去」。
 * 规则：焦点在画面时直接弹退出确认；焦点仍在底部操作栏时先把焦点交还画面，
 * 保留一步退栈；焦点在左侧导航时由更外层的导航栏分支处理退出。
 */
internal enum class RecommendationBackResult {
    CONFIRM_EXIT,
    RETURN_TO_STAGE,
    DELEGATE,
}

internal fun recommendationBackResult(
    navigationFocused: Boolean,
    actionsFocused: Boolean,
): RecommendationBackResult =
    when {
        navigationFocused -> RecommendationBackResult.DELEGATE
        actionsFocused -> RecommendationBackResult.RETURN_TO_STAGE
        else -> RecommendationBackResult.CONFIRM_EXIT
    }
