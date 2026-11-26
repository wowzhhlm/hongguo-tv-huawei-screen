// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 从桌面端导入的已注册设备身份。
 *
 * 背景（2026-11-26 深度诊断，见 logs/playback-empty-rootcause-v0220.md）：
 * TV 端每次冷启动匿名注册的新设备，播放模型接口一律返回 HTTP 200 / 0B；
 * 而桌面端复用一份 2026-11-07 注册、长期养熟的身份，此刻仍能正常出播放地址。
 * 服务端放行依赖这份身份及其 Cookie/token，而不只是请求参数或签名形态。
 *
 * 本类让 TV 直接复用这套身份，跳过匿名注册。用 [DesktopDeviceIdentity.install]
 * 在进程启动时安装到共享播放会话；安装后身份被固定，不再轮换。
 */
data class ImportedDeviceIdentity(
    val deviceId: String,
    val installId: String,
    val cdid: String,
    /** 注册响应下发的 device_token；作为播放请求头回传。 */
    val deviceToken: String = "",
    val openudid: String = "",
    /** 注册响应 Set-Cookie 里的 ttreq。 */
    val ttreq: String = "",
    /** 注册响应 Set-Cookie 里的 store-region。 */
    val storeRegion: String = "",
    /** 与 [PlaybackDeviceProfiles] 中某套画像一致，保证 query/UA/签名自洽。 */
    val profileTag: String = "xiaomi14-73932",
)

/** 桌面端 `device.json` 中当前有效的身份（2026-11-26 21:35 仍有播放进度）。 */
object DesktopDeviceIdentity {
    val identity = ImportedDeviceIdentity(
        deviceId = "4491981386945068",
        installId = "4491981386949164",
        cdid = "bacf6657-9902-4a2b-a312-1c779ca4ef03",
        deviceToken =
            "AAA43EKK5XRLD7HNADD634RLE5IVYOCM4OAH3R5M6QDOHK3ROYRRHS7XC5PQJPGNKBWYNTIOPIVLGJQY4RYDKOJ7FJILZHX25MOM7O4GOO6F4GMJ4NQXFAR4RNVMA",
        openudid = "0de2800bd4687ef9",
        // '$' 在 Kotlin 字符串里是插值前缀，必须转义。
        ttreq = "1\$db31ff011cce1ce5a4b99a751e9dd4d86bbaea4e",
        storeRegion = "cn-js",
    )

    /** 供启动阶段调用；重复安装幂等。 */
    fun install() {
        PlaybackDeviceSession.shared.installImportedIdentity(identity)
    }
}
