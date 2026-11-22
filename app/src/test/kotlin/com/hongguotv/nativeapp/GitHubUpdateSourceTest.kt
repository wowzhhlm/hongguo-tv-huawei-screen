// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GitHubUpdateSourceTest {
    private val sha = "a".repeat(64)
    private fun json(packageName: String = GitHubUpdateSource.PACKAGE_NAME, versionCode: Long = 37) =
        """{"schema":1,"packageName":"$packageName","tag":"v0.17.1-huawei-screen","versionCode":$versionCode,"versionName":"0.17.1-huawei-screen","minSdk":23,"notes":"首页即随心看","apk":{"name":"hongguo-screen-v0.17.1.apk","size":3670016,"sha256":"$sha"}}"""

    @Test
    fun parsesOwnReleaseMetadata() {
        val update = GitHubUpdateSource.parseUpdate(json())
        assertEquals(37L, update.versionCode)
        assertEquals("0.17.1-huawei-screen", update.versionName)
        assertEquals("v0.17.1-huawei-screen", update.tag)
        assertEquals("hongguo-screen-v0.17.1.apk", update.fileName)
        assertEquals("首页即随心看", update.notes)
        assertTrue(update.newerThan(36, 30))
    }

    @Test(expected = IOException::class)
    fun rejectsUpstreamPackageName() {
        GitHubUpdateSource.parseUpdate(json(packageName = "com.hongguotv.nativeapp"))
    }

    @Test(expected = IOException::class)
    fun rejectsWrongSchema() {
        GitHubUpdateSource.parseUpdate(json().replace("\"schema\":1", "\"schema\":2"))
    }

    @Test(expected = IOException::class)
    fun rejectsNonAsciiApkName() {
        GitHubUpdateSource.parseUpdate(json().replace("hongguo-screen-v0.17.1.apk", "智慧屏短剧.apk"))
    }
}
