// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/** Anonymous playback identity. Register once, then keep the same identity across repositories. */
class PlaybackDeviceSession(
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        // 注册 payload / UA / 版本必须与后续播放请求取自同一套画像，避免混合出矛盾设备。
        val device = PlaybackDeviceProfiles.current().values()
        val body = DeviceRegister.payload(device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, device).forEach { (key, value) ->
            request.header(key, value)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        // Registration has no account cookies. Cancellation belongs to the requesting work scope.
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            val text = try { cloudResponse(response) } catch (_: EmptyAccountResponseException) {
                throw EmptyPlaybackResponseException("stage=register")
            }
            DeviceRegister.parse(text, response.headers("Set-Cookie"))
        }
    },
) {
    companion object {
        val shared = PlaybackDeviceSession()
        private const val EMPTY_MODEL_PREFETCH_PAUSE_MILLIS = 5_000L
    }

    /** 画像来源；单测可替换固定画像，避免断言随全局择优状态漂移。 */
    internal var profileSource: () -> PlaybackProfile = { PlaybackDeviceProfiles.current() }

    internal data class Profile(
        val values: Map<String, String>,
        val cookie: String,
        val routes: PlaybackRoutes = PlaybackRoutes(),
        /** 该身份所用的画像标签，播放成功后据此固定可用设备。 */
        val profileTag: String = "",
        /** 与注册画像一致的 UA；播放请求头必须用它们，否则版本/机型自相矛盾。 */
        val videoUa: String = VendorConstants.VIDEO_UA,
        val playerUa: String = VendorConstants.PLAYER_UA,
    ) {
        private var playbackSigner: Signer? = null
        private var recommendationSigner: Signer? = null
        private data class Model(val entry: org.json.JSONObject, val owner: Any, val savedAt: Long)
        private val models = linkedMapOf<String, Model>()
        // Background prefetch reads only the primary model endpoint.
        @Volatile private var emptyPrimaryModelAt: Long? = null

        fun recordEmptyPrimaryModel(now: Long) { emptyPrimaryModelAt = now }

        fun recordPlayablePrimaryModel() { emptyPrimaryModelAt = null }

        fun shouldDeferPlaybackPrefetch(now: Long): Boolean =
            emptyPrimaryModelAt?.let { now - it in 0 until EMPTY_MODEL_PREFETCH_PAUSE_MILLIS } == true

        // 首页进入全集可一次性交接刚取得的模型；同仓库主动重试始终重新读取，不复用旧地址。
        @Synchronized
        fun takeModel(id: String, owner: Any, now: Long): org.json.JSONObject? {
            models.entries.removeAll { now - it.value.savedAt !in 0..15_000 }
            val model = models.remove(id) ?: return null
            return if (model.owner === owner) null else org.json.JSONObject(model.entry.toString())
        }

        @Synchronized
        fun offerModel(id: String, entry: org.json.JSONObject, owner: Any, now: Long) {
            val encoded = entry.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size > 256 * 1024) return
            models.entries.removeAll { now - it.value.savedAt !in 0..15_000 }
            models[id] = Model(org.json.JSONObject(encoded), owner, now)
            while (models.size > 6) models.remove(models.keys.first())
        }


        // 同一注册会话的进程 nonce 与启动时间保持一致，跨仓库实例也不重新生成。
        @Synchronized
        fun signer(clock: () -> Long, values: Map<String, String>): Signer =
            playbackSigner ?: Signer(clock, values).also { playbackSigner = it }

        // 推荐使用另一版本请求上下文，不能借用已绑定播放器版本的签名器。
        @Synchronized
        fun recommendationSigner(clock: () -> Long, values: Map<String, String>): Signer =
            recommendationSigner ?: Signer(clock, values).also { recommendationSigner = it }
    }

    private var cdid = UUID.randomUUID().toString()
    @Volatile private var ready: Profile? = null

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return toProfile(registerDevice(http)).also { ready = it }
    }

    internal fun shouldDeferPlaybackPrefetch(now: Long): Boolean =
        ready?.shouldDeferPlaybackPrefetch(now) == true

    @Synchronized
    internal fun existing(): Profile? = ready

    @Synchronized
    internal fun takeModel(id: String, owner: Any, now: Long) = ready?.takeModel(id, owner, now)

    /**
     * 在调用方的失败期限制内尝试替换匿名身份，部分历史采样有效，但不保证恢复。
     * 若其他仓库实例已替换 [expected]，直接复用替代身份，不重复注册。
     * 替代身份就绪时返回 true；失败时保留原身份。
     */
    @Synchronized
    internal fun rotate(http: OkHttpClient, expected: Profile? = null): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        // 共享会话的其他仓库实例已替换该失败身份，直接复用。
        if (expected != null && ready !== expected && ready != null) return true
        // 桌面端 v1.2.32：本机设备取不到播放地址时换用下一套备用设备画像（PickCustomIID）。
        // 必须在注册前轮换，让注册 payload 与后续的 toProfile 取自同一套画像，避免自相矛盾。
        val previousIndex = PlaybackDeviceProfiles.currentIndex()
        PlaybackDeviceProfiles.advance()
        // 注册失败不能消耗一套备用画像：回退尝试游标，下次仍可从这套设备重试。
        fun failed(): Boolean {
            PlaybackDeviceProfiles.rewind(previousIndex)
            return false
        }
        val device =
            try {
                register(http)
            } catch (error: InterruptedIOException) {
                // 网络超时也可能使用此异常类型，不能据此把正常工作线程标为已取消。
                null
            } catch (error: IOException) {
                null
            } ?: return failed()
        if (!validDevice(device)) return failed()
        cdid = UUID.randomUUID().toString()
        ready = toProfile(device)
        return true
    }

    /**
     * 播放取址成功后固定**这次实际使用**的画像（对应桌面端 playback_device.json）。
     * 不能读全局游标：并发轮换时它可能已被其他线程推进，导致记住错误的设备。
     */
    @Synchronized
    internal fun rememberWorkingProfile(profile: Profile) {
        val index = PlaybackDeviceProfiles.all.indexOfFirst { it.tag == profile.profileTag }
        if (index >= 0) PlaybackDeviceProfiles.rememberWorking(index)
    }

    private fun registerDevice(http: OkHttpClient): DeviceRegister.Registered {
        val device =
            try {
                register(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        if (!validDevice(device)) throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return device
    }

    private fun validDevice(device: DeviceRegister.Registered) =
        validId.matches(device.deviceId) && validId.matches(device.installId)

    private fun toProfile(device: DeviceRegister.Registered): Profile {
        // 注册 payload、播放 query、UA 与 medusa 必须来自同一套自洽画像。
        // 旧实现固定用 VendorConstants.device，而注册已改用画像池，导致身份自相矛盾。
        val source = profileSource()
        return Profile(
            source.values() +
                mapOf(
                    "device_id" to device.deviceId,
                    "iid" to device.installId,
                    "cdid" to cdid,
                ),
            device.cookies
                .filterKeys {
                    it in setOf("install_id", "ttreq", "store-region", "store-region-src")
                }
                .entries
                .joinToString("; ") { (name, value) -> "$name=$value" },
            profileTag = source.tag,
            videoUa = source.videoUa,
            playerUa = source.playerUa,
        )
    }
}

private val validId = Regex("[1-9][0-9]{0,29}")
