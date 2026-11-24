// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 播放设备画像池。
 *
 * ## 背景（2026-11-23）
 *
 * 上游对播放模型接口做静默过滤：命中风控时返回 HTTP 200 + 0 字节，而不是错误码。
 * 桌面端 v1.2.32（2026-11-23 发布）修复「获取播放地址失败」的做法是：
 *  * 官方接口改了验证方式 → 新增 `/novel/player/multi_video_model/default/v1`；
 *  * 本机设备取不到播放地址时自动换用备用设备（`playback_device.json`、
 *    `CustomDevice` / `verifyIID` / `PickCustomIID` / `isBlocked` / `sampleVideoID`）；
 *  * 记住「最近能出数据的那台设备」，下次优先复用。
 *
 * 反查 TV 端旧实现，画像在三处互相矛盾：注册用 `VendorConstants.device`
 * （71332 / Redmi / Android 16），`ContentRepository.playbackRequest()` 覆盖成
 * 73932，`MobilePlayback.query()` 又硬编码 74332 / PGT-AN10 / vivo_8662_64，
 * UA 里再出现 73932。这种「跨设备拼接」正是上游核验设备身份时最容易被判异常的组合。
 *
 * ## 设计
 *
 * 每套画像内部**完全自洽**：version_code / version_name / manifest_version_code /
 * update_version_code / pv_player 一致；device_type / device_brand / os_version /
 * os_api / resolution / dpi / channel 互相匹配；UA 里的版本号、机型与 query 一致。
 * 池按「最近成功优先」排序，失败时 [advance] 轮换到下一套，成功时 [rememberWorking]
 * 固定住可用画像，对应桌面端 `playback_device.json` 的持久化择优。
 *
 * 本文件只含纯数据与纯逻辑，不发起网络请求、不持有身份（device_id / iid / Cookie
 * 仍由 [DeviceRegister] 匿名注册下发），便于单测与代码审查。
 */
internal data class PlaybackProfile(
    /** 诊断用短代号，不含身份信息。 */
    val tag: String,
    /** 覆盖 [VendorConstants.device] 的字段；未列出的字段沿用基准值。 */
    val fields: Map<String, String>,
    /** 播放模型请求的 UA，必须与 query 里的版本、机型一致。 */
    val videoUa: String,
    /** 播放器链路的 UA。 */
    val playerUa: String,
    /** 注册 / 内容接口的 UA。 */
    val appUa: String,
) {
    /**
     * 与 [VendorConstants.device] 合并后的完整设备字段。
     *
     * UA 由 [videoUa] / [playerUa] / [appUa] 单独提供，避免把 UA 塞进 query 参数
     * 改变签名形态；`user_agent` 字段沿用 fields 中的定义供需要的地方读取。
     */
    fun values(): Map<String, String> = VendorConstants.device + fields
}

/** 记住最近可播画像的持久化桥；TV app 用 SharedPreferences 实现，core 不依赖 Android。 */
interface PlaybackProfileStore {
    fun load(): String?
    fun save(tag: String?)
}

object PlaybackDeviceProfiles {
    /** 宿主注入的持久化存储；未注入时择优只在本进程内生效。 */
    @Volatile
    var store: PlaybackProfileStore? = null

    /** 画像顺序即首次尝试顺序：桌面端当天实测可播的画像优先。 */
    internal val all: List<PlaybackProfile> = listOf(
        // 桌面端 device.json（2026-11-23 15:24 仍有播放记录），全字段自洽。
        PlaybackProfile(
            tag = "xiaomi14-73932",
            fields = mapOf(
                "version_code" to "73932", "version_name" to "7.3.9.32",
                "manifest_version_code" to "73932", "update_version_code" to "73932",
                "pv_player" to "73932", "device_type" to "23127PN0CC",
                "device_brand" to "xiaomi", "os_version" to "14", "os_api" to "34",
                "resolution" to "1200*2670", "dpi" to "460", "channel" to "xiaomi_8662_64",
                "user_agent" to "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)",
            ),
            videoUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)",
            playerUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001;tt-ok/3.12.13.20)",
            appUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001)",
        ),
        // 当前 TV 主链路使用的机型，改为自洽（原先 UA 写 73932、query 写 74332）。
        PlaybackProfile(
            tag = "pgt-74332",
            fields = mapOf(
                "version_code" to "74332", "version_name" to "7.4.3.32",
                "manifest_version_code" to "74332", "update_version_code" to "74332",
                "pv_player" to "74332", "device_type" to "PGT-AN10",
                // PGT-AN10 / V417IR / vivo_8662_64 是同一台 vivo 设备，不能写成 HONOR。
                "device_brand" to "vivo", "os_version" to "12", "os_api" to "31",
                "resolution" to "1200*2672", "dpi" to "460", "channel" to "vivo_8662_64",
                "user_agent" to "com.phoenix.read/74332 (Linux; U; Android 12; zh_CN; PGT-AN10; Build/V417IR; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)",
            ),
            videoUa = "com.phoenix.read/74332 (Linux; U; Android 12; zh_CN; PGT-AN10; Build/V417IR; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)",
            playerUa = "com.phoenix.read/74332 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
            appUa = "com.phoenix.read/74332 (Linux; U; Android 12; zh_CN; PGT-AN10; Build/V417IR)",
        ),
        // 桌面端 v1.2.32 内置备用机型之一：三星旗舰。
        PlaybackProfile(
            tag = "samsung-73932",
            fields = mapOf(
                "version_code" to "73932", "version_name" to "7.3.9.32",
                "manifest_version_code" to "73932", "update_version_code" to "73932",
                "pv_player" to "73932", "device_type" to "SM-S9280",
                "device_brand" to "samsung", "os_version" to "14", "os_api" to "34",
                "resolution" to "1440*3120", "dpi" to "560", "channel" to "samsung_8662_64",
                "user_agent" to "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; SM-S9280; Build/UP1A.231005.007; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)",
            ),
            videoUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; SM-S9280; Build/UP1A.231005.007; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)",
            playerUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; SM-S9280; Build/UP1A.231005.007;tt-ok/3.12.13.20)",
            appUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; SM-S9280; Build/UP1A.231005.007)",
        ),
        // 华为 / HarmonyOS 机型，与智慧屏运行环境最接近。
        PlaybackProfile(
            tag = "honor-73932",
            fields = mapOf(
                "version_code" to "73932", "version_name" to "7.3.9.32",
                "manifest_version_code" to "73932", "update_version_code" to "73932",
                "pv_player" to "73932", "device_type" to "BVL-AN16",
                "device_brand" to "HONOR", "os_version" to "14", "os_api" to "34",
                "resolution" to "1264*2780", "dpi" to "460", "channel" to "honor_8662_64",
                "user_agent" to "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; BVL-AN16; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)",
            ),
            videoUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; BVL-AN16; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)",
            playerUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; BVL-AN16; Build/UKQ1.230804.001;tt-ok/3.12.13.20)",
            appUa = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; BVL-AN16; Build/UKQ1.230804.001)",
        ),
        // 旧 TV 固定画像保留为最后兜底。playerUa 历史写成 71532/SM-N9860，与 query 的
        // 71332/25053RT47C 自相矛盾；这里按 version_code 71332 对齐，消除最后一处拼接。
        PlaybackProfile(
            tag = "redmi-71332",
            fields = emptyMap(),
            videoUa = VendorConstants.VIDEO_UA,
            playerUa = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3;tt-ok/3.12.13.20)",
            appUa = VendorConstants.APP_UA,
        ),
    )

    @Volatile
    private var workingIndex: Int = 0

    @Volatile
    private var cursor: Int = 0

    /** 当前应使用的画像。注册、query、UA 必须全部取自这里，避免再次拼接出矛盾画像。 */
    @Synchronized
    internal fun current(): PlaybackProfile = all[cursor.coerceIn(0, all.lastIndex)]

    @Synchronized
    fun currentIndex(): Int = cursor.coerceIn(0, all.lastIndex)

    /** 取不到播放地址时轮换到下一套备用设备（对应桌面端 PickCustomIID）。 */
    @Synchronized
    internal fun advance(): PlaybackProfile {
        cursor = (cursor + 1) % all.size
        return all[cursor]
    }

    /** 某套画像成功出数据后固定住它，并写入宿主持久化存储（对应桌面端 playback_device.json）。 */
    @Synchronized
    fun rememberWorking(index: Int = cursor) {
        if (index in all.indices) {
            workingIndex = index
            cursor = index
            store?.save(all[index].tag)
        }
    }

    /** 注册失败只回退尝试游标，不改写已记住的可用画像，避免白白消耗一套备用设备。 */
    @Synchronized
    internal fun rewind(index: Int) {
        cursor = index.coerceIn(0, all.lastIndex)
    }

    /** 最近一次可出数据的画像；继续失败时回退到它，避免每次从零开始撞空响应。 */
    @Synchronized
    fun restorePreferred() {
        cursor = workingIndex.coerceIn(0, all.lastIndex)
    }

    /** 冷启动时从宿主存储恢复上次记住的可用画像。 */
    @Synchronized
    fun restoreFromStore() {
        val tag = store?.load() ?: return
        val index = all.indexOfFirst { it.tag == tag }
        if (index >= 0) {
            workingIndex = index
            cursor = index
        }
    }

    /** 仅供单测：重置进程内择优状态。 */
    @Synchronized
    internal fun resetForTest() {
        workingIndex = 0
        cursor = 0
        store = null
    }
}
