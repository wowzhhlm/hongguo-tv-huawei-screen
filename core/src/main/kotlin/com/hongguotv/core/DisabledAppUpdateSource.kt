// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import java.io.IOException

/**
 * 华为智慧屏定制版专用的“禁用应用内更新”数据源。
 *
 * 定制版使用独立的应用签名与包名，若沿用上游的更新源，会拉到官方签名的 APK，
 * 导致覆盖安装时签名冲突（表现为“应用未安装”）。因此这里统一禁用检查与下载，
 * 提示用户到项目发布页手动下载新版本后用 U 盘/华为分享覆盖安装。
 *
 * 这是一个空实现：不发起任何网络请求，不写任何文件。
 */
class DisabledAppUpdateSource : AppUpdateSource {
    override fun latest(): AppUpdate =
        throw IOException("定制版已关闭应用内自动更新，请到项目发布页下载新版后手动安装")

    override fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        throw IOException("定制版不支持应用内下载，请手动安装新版")
    }

    override fun cancel() = Unit

    override fun close() = Unit
}
