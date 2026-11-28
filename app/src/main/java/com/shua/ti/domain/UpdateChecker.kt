package com.shua.ti.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateResult {
    data class Available(
        val latestVersion: String,
        val notes: String,
        val downloadUrl: String,
    ) : UpdateResult

    data object UpToDate : UpdateResult

    data class Failed(val message: String) : UpdateResult
}

/**
 * 检查 GitHub Releases 是否有新版本。
 *
 * 只做一次手动触发的请求（设置页按钮），不在启动时静默联网：
 * 应用「无后台网络请求」的定位不变。
 *
 * [fetch] 可注入，便于单测用固定 JSON 覆盖解析与版本比较。
 */
object UpdateChecker {

    private const val API_URL = "https://api.github.com/repos/losseeer/shuaTI/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(
        currentVersion: String,
        fetch: suspend (String) -> String = ::httpGet,
    ): UpdateResult {
        val body = try {
            fetch(API_URL)
        } catch (e: Exception) {
            return UpdateResult.Failed("检查更新失败：${e.message ?: "网络不可用"}")
        }
        val release = parseRelease(body)
            ?: return UpdateResult.Failed("检查更新失败：返回内容无法识别")
        return if (compareVersions(release.tagName, currentVersion) > 0) {
            if (release.apkUrl.isNullOrBlank()) {
                UpdateResult.Failed("发现 ${release.tagName}，但该版本没有提供 APK 下载")
            } else {
                UpdateResult.Available(
                    latestVersion = release.tagName.removePrefix("v"),
                    notes = release.notes,
                    downloadUrl = release.apkUrl,
                )
            }
        } else {
            UpdateResult.UpToDate
        }
    }

    data class Release(
        val tagName: String,
        val notes: String,
        val apkUrl: String?,
    )

    /** 解析 release JSON；字段缺失时返回 null，不臆造默认值。 */
    fun parseRelease(body: String): Release? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val apkUrl = (root["assets"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { asset ->
                asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true
            }
            ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        return Release(tagName = tag, notes = notes, apkUrl = apkUrl)
    }

    /**
     * 语义化版本比较：忽略前导 v 与 -rc 之类后缀，只比数字段。
     * @return 正数表示 a 更新，0 相同，负数表示 b 更新。
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): List<Int> =
            v.trim().removePrefix("v").substringBefore('-')
                .split('.')
                .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }

        val pa = parts(a)
        val pb = parts(b)
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "shuaTI-update-check")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("服务器返回 HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
