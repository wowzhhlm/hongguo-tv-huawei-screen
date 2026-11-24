package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 韧性取址（桌面端 v1.2.32 移植）：官方新验证端点 default/v1 + 备用设备轮换。
 */
class BackupDevicePlaybackTest {
    private val model =
        """{"code":0,"data":{"1004":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}}}"""

    @Before
    fun reset() = PlaybackDeviceProfiles.resetForTest()

    private fun response(request: Request, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body.toResponseBody()).build()

    /**
     * 主 player/v1 空响应，但 default/v1 返回可播模型：韧性链路应命中新端点并返回。
     * 同时验证请求头 UA 来自注册画像（不再是硬编码的 71532/SM-N9860）。
     */
    @Test
    fun defaultEndpointRecoversWhenPrimaryModelEmpty() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when (request.url.encodedPath) {
                "/novel/player/multi_video_model/player/v1" -> ""
                "/novel/player/multi_video_model/default/v1" -> model
                else -> ""
            }
            response(request, body)
        }.build()
        val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
        val ladder = ContentRepository(http = client, playbackSession = session)
            .streamLadderWithBackup("123")

        assertEquals(1, ladder.renditions.size)
        assertEquals("https://example.test/video", ladder.renditions.first().url)
        assertTrue("default/v1 must be tried", requests.any {
            it.url.encodedPath == "/novel/player/multi_video_model/default/v1"
        })
        val defaultRequest = requests.first { it.url.encodedPath == "/novel/player/multi_video_model/default/v1" }
        val profileUa = session.profile(client).playerUa
        assertEquals("UA must come from registered profile", profileUa, defaultRequest.header("User-Agent"))
    }

    /**
     * 所有画像的播放端点都空响应：轮换有界停止，注册次数不超过轮换轮数 + 首次。
     * 最终抛 EmptyPlaybackResponseException，而不是无限注册或成功。
     */
    @Test
    fun backupDeviceRotationIsBoundedWhenAllModelsEmpty() {
        val registrations = AtomicInteger()
        // 注入 register lambda 时不再走真实注册网络请求，所以在 lambda 内计数。
        val session = PlaybackDeviceSession {
            registrations.incrementAndGet()
            DeviceRegister.Registered("333", "444")
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), "")
        }.build()
        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            ContentRepository(http = client, playbackSession = session)
                .streamLadderWithBackup("123")
        }
        assertTrue(error.allPlaybackModelsEmpty || error.detail == "stage=backup-devices-exhausted")
        assertTrue("rotation must be bounded", registrations.get() <= BACKUP_DEVICE_ROUNDS)
        assertTrue("at least primary device attempted", registrations.get() >= 1)
    }

    /** 业务错误码不应触发设备轮换：换设备也救不了业务拒绝，应立刻上抛。 */
    @Test
    fun businessErrorDoesNotRotateDevices() {
        val registrations = AtomicInteger()
        val session = PlaybackDeviceSession {
            registrations.incrementAndGet()
            DeviceRegister.Registered("333", "444")
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), """{"code":110001}""")
        }.build()
        assertThrows(Exception::class.java) {
            ContentRepository(http = client, playbackSession = session)
                .streamLadderWithBackup("123")
        }
        assertEquals("business error must not rotate", 1, registrations.get())
    }
}
