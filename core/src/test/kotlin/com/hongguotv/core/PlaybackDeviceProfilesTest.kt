package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 备用设备画像池：对齐桌面端 v1.2.32「本机设备取不到播放地址时自动换备用设备」。
 * 每套画像必须内部自洽（版本/机型/channel/UA 一致），轮换有界，成功后能固定可用画像。
 */
class PlaybackDeviceProfilesTest {
    @Before
    fun reset() = PlaybackDeviceProfiles.resetForTest()

    @Test
    fun everyProfileIsInternallyConsistent() {
        for (profile in PlaybackDeviceProfiles.all) {
            val v = profile.values()
            assertEquals("版本名必须与 version_code 同代 ${profile.tag}",
                profile.tag.substringAfterLast('-'), v.getValue("version_code"))
            assertEquals("manifest/update/pv 必须同一版本 ${profile.tag}",
                v.getValue("version_code"), v.getValue("manifest_version_code"))
            assertEquals(v.getValue("version_code"), v.getValue("update_version_code"))
            assertEquals(v.getValue("version_code"), v.getValue("pv_player"))
            assertTrue("videoUa 必须带 version_code ${profile.tag}",
                profile.videoUa.contains("com.phoenix.read/${v.getValue("version_code")} ("))
            assertTrue("playerUa 必须带 version_code ${profile.tag}",
                profile.playerUa.contains("com.phoenix.read/${v.getValue("version_code")} ("))
            // UA 里用营销机型（如 Xiaomi 14），query 用 model code（如 23127PN0CC），
            // 这是行业惯例，不要求字面相同；只要求 UA 承载同一 Android 大版本。
            assertTrue("UA 必须带 Android 主版本 ${profile.tag}",
                profile.videoUa.contains("Android ${v.getValue("os_version")};"))
            assertTrue("注册/播放 UA 字段值应等于 videoUa ${profile.tag}",
                profile.values()["user_agent"] == null || profile.values()["user_agent"] == profile.videoUa)
        }
    }

    @Test
    fun versionNameMatchesVersionCodeFamily() {
        // 红果版本约定：version_code 7AB32 对应 version_name 7.A.B.32。
        val expected = mapOf(
            "73932" to "7.3.9.32", "74332" to "7.4.3.32", "71332" to "7.1.3.32",
        )
        for (profile in PlaybackDeviceProfiles.all) {
            val v = profile.values()
            assertEquals("version_name 必须与 version_code 同代 ${profile.tag}",
                expected[v.getValue("version_code")], v.getValue("version_name"))
        }
    }

    @Test
    fun advanceRotatesThroughAllProfilesAndWraps() {
        val first = PlaybackDeviceProfiles.current()
        val seen = linkedSetOf(first.tag)
        repeat(PlaybackDeviceProfiles.all.size - 1) {
            seen += PlaybackDeviceProfiles.advance().tag
        }
        assertEquals(PlaybackDeviceProfiles.all.size, seen.size)
        // 再轮换一次回到起点，形成有界环。
        assertEquals(first.tag, PlaybackDeviceProfiles.advance().tag)
    }

    @Test
    fun rememberWorkingFixesNextColdStartChoice() {
        PlaybackDeviceProfiles.advance()
        PlaybackDeviceProfiles.advance()
        val working = PlaybackDeviceProfiles.current()
        PlaybackDeviceProfiles.rememberWorking()
        // 模拟后续失败把 cursor 推走，再恢复应回到记住的画像。
        PlaybackDeviceProfiles.advance()
        PlaybackDeviceProfiles.restorePreferred()
        assertEquals(working.tag, PlaybackDeviceProfiles.current().tag)
    }

    @Test
    fun workingProfileSurvivesProcessRestartViaStore() {
        var saved: String? = null
        PlaybackDeviceProfiles.store = object : PlaybackProfileStore {
            override fun load(): String? = saved
            override fun save(tag: String?) { saved = tag }
        }
        PlaybackDeviceProfiles.advance()
        val working = PlaybackDeviceProfiles.current()
        PlaybackDeviceProfiles.rememberWorking()

        // 模拟冷启动：进程内择优状态全部回到默认，只靠宿主存储恢复。
        PlaybackDeviceProfiles.resetForTest()
        PlaybackDeviceProfiles.store = object : PlaybackProfileStore {
            override fun load(): String? = saved
            override fun save(tag: String?) { saved = tag }
        }
        PlaybackDeviceProfiles.restoreFromStore()
        assertEquals(working.tag, PlaybackDeviceProfiles.current().tag)
    }

    @Test
    fun rewindDoesNotOverwriteRememberedProfile() {
        val working = PlaybackDeviceProfiles.current()
        PlaybackDeviceProfiles.rememberWorking()
        val attempted = PlaybackDeviceProfiles.advance()
        PlaybackDeviceProfiles.rewind(PlaybackDeviceProfiles.all.indexOf(attempted) - 1)
        PlaybackDeviceProfiles.restorePreferred()
        assertEquals(working.tag, PlaybackDeviceProfiles.current().tag)
    }
}
