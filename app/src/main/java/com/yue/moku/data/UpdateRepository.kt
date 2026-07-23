package com.yue.moku.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val publishedAt: String,
)

class UpdateRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun fetchLatest(apiUrl: String): GitHubRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub API ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val tagName = json.optString("tag_name")
            val name = json.optString("name").ifBlank { tagName }
            val body = json.optString("body")
            val htmlUrl = json.optString("html_url")
            val publishedAt = json.optString("published_at")
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val browserUrl = asset.optString("browser_download_url")
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = browserUrl
                        break
                    }
                }
            }
            if (tagName.isBlank()) throw IOException("GitHub release tag 缺失")
            GitHubRelease(tagName, name, body, htmlUrl, apkUrl, publishedAt)
        }
    }

    suspend fun downloadApk(
        url: String,
        outputFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败：${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            val total = body.contentLength()
            outputFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8 * 1024)
                    var downloaded = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastEmit > 50_000 || downloaded == total) {
                            onProgress(downloaded, total)
                            lastEmit = downloaded
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
            outputFile
        }
    }
}
