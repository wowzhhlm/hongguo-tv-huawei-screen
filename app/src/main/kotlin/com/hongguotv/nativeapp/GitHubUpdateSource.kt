// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateSource
import com.hongguotv.core.UpdateFiles
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 更新源指向本项目自己的 GitHub Release（而非上游 hongguoTV）。
 * update.json 与 APK 均为本仓库 Release 附件，下载后沿用 AppUpdater 的
 * SHA256 / 包名 / 版本 / v1+v2 签名四重校验，避免被替换成官方签名包。
 */
internal class GitHubUpdateSource : AppUpdateSource {
    companion object {
        const val REPOSITORY = "wowzhhlm/hongguo-tv-huawei-screen"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val PACKAGE_NAME = "com.hongguotv.huawei.screen"
        private const val MIRROR = "https://ghfast.top/"

        internal fun parseUpdate(text: String): AppUpdate {
            val json =
                try {
                    JSONObject(text)
                } catch (_: Exception) {
                    throw IOException("更新信息格式不正确")
                }
            if (json.optInt("schema") != 1 || json.optString("packageName") != PACKAGE_NAME)
                throw IOException("更新信息与本应用不匹配")
            val tag = json.optString("tag")
            val apk = json.optJSONObject("apk") ?: throw IOException("更新信息缺少安装包")
            val name = apk.optString("name")
            val versionName = json.optString("versionName")
            val sha = apk.optString("sha256").lowercase()
            val versionCode = json.optLong("versionCode")
            val size = apk.optLong("size")
            val minSdk = json.optInt("minSdk", 23)
            if (
                !tag.matches(Regex("v[0-9][0-9A-Za-z.+-]{0,60}")) ||
                !name.matches(Regex("[A-Za-z0-9._+-]+\\.apk")) ||
                !versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")) ||
                versionCode !in 1..Int.MAX_VALUE.toLong() ||
                size !in 1..AppUpdate.MAX_APK_BYTES ||
                !sha.matches(Regex("[0-9a-f]{64}")) ||
                minSdk !in 21..100
            )
                throw IOException("更新信息格式不正确")
            return AppUpdate(versionCode, versionName, minSdk, tag, name, size, sha, json.optString("notes").take(1500))
        }
        private val HOSTS =
            setOf("ghfast.top", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }

    private val http =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != "https" || url.host !in HOSTS) throw IOException("更新下载地址不受支持")
                chain.proceed(chain.request())
            }
            .build()
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val cancelled = AtomicBoolean(false)

    private fun request(url: String) =
        Request.Builder()
            .url(url)
            .header("User-Agent", "HuaweiScreen-Updater")
            .header("Cache-Control", "no-cache")
            .build()

    /** 原站优先，镜像兜底；镜像只转发公开 Release 文件，不携带任何凭据。 */
    private fun <T> fromSources(origin: String, seconds: Long, action: (okhttp3.Response) -> T): T {
        var failure: IOException? = null
        for (url in listOf(origin, MIRROR + origin)) {
            if (cancelled.get()) throw IOException("检查已取消")
            val call = http.newCall(request(url))
            call.timeout().timeout(seconds, TimeUnit.SECONDS)
            activeCalls.add(call)
            try {
                if (cancelled.get()) throw IOException("检查已取消")
                return call.execute().use { response ->
                    if (cancelled.get()) throw IOException("检查已取消")
                    if (!response.isSuccessful) throw IOException("更新源暂不可用（HTTP ${response.code}）")
                    action(response)
                }
            } catch (error: IOException) {
                if (cancelled.get()) throw error
                failure = error
            } finally {
                activeCalls.remove(call)
            }
        }
        throw failure ?: IOException("更新源暂不可用，请稍后重试")
    }

    override fun latest(): AppUpdate {
        cancelled.set(false)
        return fromSources("$RELEASES/latest/download/update.json", 15) { response ->
            val bytes =
                response.body?.byteStream()?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 65536) throw IOException("更新信息过大")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: throw IOException("更新信息为空")
            parseUpdate(bytes.toString(Charsets.UTF_8))
        }
    }

    override fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        cancelled.set(false)
        if (UpdateFiles.verify(file, update)) return
        fromSources("$RELEASES/download/${update.tag}/${update.fileName}", 300) { response ->
            val body = response.body ?: throw IOException("安装包为空")
            if (body.contentLength() >= 0 && body.contentLength() != update.size)
                throw IOException("安装包长度异常")
            progress(0)
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeCalls.forEach { it.cancel() }
    }

    override fun close() {
        cancel()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
