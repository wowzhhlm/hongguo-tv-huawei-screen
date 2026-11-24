// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Signing returns the final URL because v5 selects its digest branch through the request ticket. */
internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)

internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest
}

internal object MobilePlayback {
    private const val PRIMARY_MODEL_HOST = "api5-normal-sinfonlinea.fqnovel.com"
    private val v5Signer = Signer(mobileV5 = true)
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val request = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(request.url, request.headers)
    }

    /**
     * 播放 query 必须与注册 payload 取自同一套画像。旧实现把版本/机型/channel 写死成
     * 74332/PGT-AN10/vivo_8662_64，而注册用 73932/Xiaomi 14，签名 medusa 又是第三套机型；
     * 这种跨设备拼接是服务端静默回 200/0B 的高概率诱因。缺省值保留历史形态，单测注入的
     * 最小 device map 仍可工作；生产用 [PlaybackDeviceProfiles] 的完整画像保持自洽。
     */
    private fun query(device: Map<String, String>) =
        linkedMapOf("aid" to "8662", "app_name" to "novelread",
            "version_code" to (device["version_code"] ?: "74332"),
            "version_name" to (device["version_name"] ?: "7.4.3.32"),
            "channel" to (device["channel"] ?: "vivo_8662_64"),
            "device_platform" to "android",
            "device_type" to (device["device_type"] ?: "PGT-AN10"),
            "os_version" to (device["os_version"] ?: "12"),
            "update_version_code" to (device["update_version_code"] ?: "74332"),
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"))

    /** The player GET also requires v5, including MD5(empty) rather than a zero body digest.
     * Wire profile verified against hongguo-drama 92df757 and hongguo-tv 9e7386f.
     */
    fun videoTabRequest(id: String, device: Map<String, String>, host: String, now: Long,
                        signing: MobilePlaybackSigner = signer,
                        userAgent: String? = null): Request.Builder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val values = query(device) + linkedMapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
            "video_platform" to "1024", "source" to "0", "from_video_id" to "", "_rticket" to now.toString(),
        )
        val url = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"
            .toHttpUrl().newBuilder().apply { values.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val signed = signing.sign(url.toString(), ByteArray(0), device)
        RequestScope.ensureActive()
        return Request.Builder().url(signed.url).get()
            .header("User-Agent", userAgent ?: "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                signed.headers.forEach { (k, v) -> header(k, v) }
                // Empty bytes are signing input only: the GET has no body or POST headers.
                removeHeader("x-ss-stub")
                removeHeader("Content-Type")
                removeHeader("Content-Encoding")
                // User-Agent 不参与签名；强制对齐注册画像的 UA。
                if (userAgent != null) header("User-Agent", userAgent!!)
            }
    }

    /**
     * 桌面端 v1.2.32 适配的「官方接口改验证方式」新端点：`default/v1`。
     *
     * 与 [ladder] 的 `player/v1` 使用同一套 query/body/v5 签名，只切换路径；
     * 正式链路在主端点空响应后把它作为下一条候选，提高命中放行分支的机会。
     * [userAgent] 传入当前注册画像的 UA，保证与 query/注册身份自洽。
     */
    fun defaultModelLadder(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signing: MobilePlaybackSigner,
        now: Long,
        host: String = PRIMARY_MODEL_HOST,
        userAgent: String? = null,
    ): StreamLadder = modelLadder(id, device, http, signing, now, host,
        "/novel/player/multi_video_model/default/v1", userAgent)

    private fun modelLadder(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signing: MobilePlaybackSigner,
        now: Long,
        host: String,
        path: String,
        userAgent: String?,
    ): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val values = query(device) + ("_rticket" to now.toString())
        val url = "https://$host$path"
            .toHttpUrl().newBuilder().apply { values.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val payload = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent ?: "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
            .header("Accept", "application/json").header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        val signed = signing.sign(url.toString(), bytes, device)
        request.url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        // User-Agent 不参与签名计算；覆盖为注册画像的 UA 保持与 query/注册身份自洽。
        if (userAgent != null) request.header("User-Agent", userAgent)
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
            val body = response.body ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val limit = 2 * 1024 * 1024L
            if (body.contentLength() > limit) throw IOException("播放信息过大")
            val source = body.source()
            source.request(limit + 1)
            val bytesRead = source.buffer.size
            if (bytesRead > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) {
                if (!response.header("Retry-After").isNullOrBlank()) throw PlaybackServiceBackoffException()
                throw EmptyPlaybackResponseException("stage=default-model").apply {
                    addSuppressed(PlaybackDiagnosticException("default-model=${response.code}/${bytesRead}B"))
                }
            }
            val root = try { JSONObject(text) } catch (_: Exception) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            PlaybackModels.checkStatus(root)
            val data = root.optJSONObject("data")
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val entries = data.optJSONObject("1004") ?: data
            val model = PlaybackModels.objectValue(entries.optJSONObject(id)?.opt("video_model"))
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            val rows = PlaybackModels.renditions(model)
            if (rows.isEmpty()) throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            StreamLadder(rows)
        }
    }

    fun ladder(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        host: String = PRIMARY_MODEL_HOST,
        userAgent: String? = null,
    ): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val query = query(device) + ("_rticket" to now.toString())
        val url = "https://$host/novel/player/multi_video_model/player/v1"
            .toHttpUrl().newBuilder().apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        // TV uses platform decoders, not the official app's bytevc2 decoder.
        // Level 3 can return only bytevc2; level 1 negotiates compatible renditions.
        val payload = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent ?: "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
            .header("Accept", "application/json").header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        val signed = signer.sign(url.toString(), bytes, device)
        request.url(signed.url)
        signed.headers.forEach { (k,v) -> request.header(k,v) }
        // User-Agent 不参与签名计算；签名器给的是占位机型，覆盖为注册画像的 UA 保持自洽。
        if (userAgent != null) request.header("User-Agent", userAgent)
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
            val body = response.body ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val limit = 2 * 1024 * 1024L
            if (body.contentLength() > limit) throw IOException("播放信息过大")
            val source = body.source()
            source.request(limit + 1)
            val bytesRead = source.buffer.size
            if (bytesRead > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) {
                if (!response.header("Retry-After").isNullOrBlank()) throw PlaybackServiceBackoffException()
                throw EmptyPlaybackResponseException("stage=mobile-model").apply {
                    addSuppressed(PlaybackDiagnosticException("mobile-model=${response.code}/${bytesRead}B"))
                }
            }
            val root = try { JSONObject(text) } catch (_: Exception) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            PlaybackModels.checkStatus(root)
            val data = root.optJSONObject("data")
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val entries = data.optJSONObject("1004") ?: data
            val model = PlaybackModels.objectValue(entries.optJSONObject(id)?.opt("video_model"))
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            val rows = PlaybackModels.renditions(model)
            if (rows.isEmpty()) throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            StreamLadder(rows)
        }
    }
}
