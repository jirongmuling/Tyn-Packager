package com.tyranor.next.packager.cover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 封面抓取候选。 */
data class CoverCandidate(
    val id: String,
    val title: String,
    val developer: String,
    val released: String,
    /** 大图地址。 */
    val coverUrl: String,
    /** 缩略图地址。 */
    val thumbnailUrl: String,
)

/**
 * 游戏封面抓取。
 *
 * 复刻 Tyranor 的封面来源逻辑：按游戏标题查询 **VNDB**（公开 API，无需鉴权），
 * 取首条命中项的封面图作为自定义图标。
 *
 * 注意：VNDB 只收录视觉小说，非 Galgame 命中率低；失败时由调用方回退到手动选图。
 */
object CoverFetcher {

    private const val ENDPOINT = "https://api.vndb.org/kana/vn"
    private const val FIELDS =
        "title,alttitle,released,image.url,image.thumbnail,developers.name"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_JSON_CHARS = 512 * 1024

    /**
     * 按标题搜索封面候选。
     *
     * @param query 通常传游戏目录名或标题
     * @param max 最多返回条数
     */
    suspend fun search(query: String, max: Int = 5): List<CoverCandidate> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            val body = JSONObject().apply {
                put("filters", org.json.JSONArray().put("search").put("=").put(query.trim()))
                put("fields", FIELDS)
                put("results", max.coerceIn(1, 10))
            }.toString()

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                // VNDB 要求带可识别的 User-Agent
                setRequestProperty("User-Agent", "TynPackager/0.1 (Android)")
            }

            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) return@withContext emptyList()

                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    var ch: Int
                    while (reader.read().also { ch = it } >= 0 && sb.length < MAX_JSON_CHARS) {
                        sb.append(ch.toChar())
                    }
                    sb.toString()
                }
                parse(text)
            } catch (_: Exception) {
                emptyList()
            } finally {
                runCatching { conn.disconnect() }
            }
        }

    private fun parse(json: String): List<CoverCandidate> = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("results") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val image = item.optJSONObject("image") ?: continue
                val url = image.optString("url")
                if (url.isBlank()) continue
                add(
                    CoverCandidate(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        developer = item.optJSONArray("developers")
                            ?.optJSONObject(0)?.optString("name").orEmpty(),
                        released = item.optString("released"),
                        coverUrl = url,
                        thumbnailUrl = image.optString("thumbnail").ifBlank { url },
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }

    /** 下载封面图片到本地临时文件。 */
    suspend fun download(url: String, target: File): File? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "TynPackager/0.1 (Android)")
            }
            conn.inputStream.use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            target.takeIf { it.isFile && it.length() > 0 }
        }.getOrNull()
    }

    /** 编码查询串（VNDB 用的是 JSON body，这里保留给其它来源复用）。 */
    internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
