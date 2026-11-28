package com.shua.ti.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val releaseJson = """
        {
          "tag_name": "v0.1.4",
          "name": "shuaTI 0.1.4",
          "body": "## 改进\n- 回看与收藏",
          "assets": [
            {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"},
            {"name": "shuaTI-0.1.4.apk", "browser_download_url": "https://example.com/app.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun `newer tag is available`() = runBlocking {
        val result = UpdateChecker.check("0.1.3") { releaseJson }
        assertTrue(result is UpdateResult.Available)
        val available = result as UpdateResult.Available
        assertEquals("0.1.4", available.latestVersion)
        assertEquals("https://example.com/app.apk", available.downloadUrl)
        assertTrue(available.notes.contains("回看"))
    }

    @Test
    fun `same version is up to date`() = runBlocking {
        val result = UpdateChecker.check("0.1.4") { releaseJson }
        assertEquals(UpdateResult.UpToDate, result)
    }

    @Test
    fun `older current version means up to date`() = runBlocking {
        val result = UpdateChecker.check("0.2.0") { releaseJson }
        assertEquals(UpdateResult.UpToDate, result)
    }

    @Test
    fun `network error surfaces as failed with message`() = runBlocking {
        val result = UpdateChecker.check("0.1.3") { throw java.io.IOException("timeout") }
        assertTrue(result is UpdateResult.Failed)
        assertTrue((result as UpdateResult.Failed).message.contains("timeout"))
    }

    @Test
    fun `unrecognised json is failed not crash`() = runBlocking {
        val result = UpdateChecker.check("0.1.3") { "not json" }
        assertTrue(result is UpdateResult.Failed)
    }

    @Test
    fun `release without apk asset reports failure`() = runBlocking {
        val noApk = """{"tag_name":"v9.0.0","body":"","assets":[]}"""
        val result = UpdateChecker.check("0.1.3") { noApk }
        assertTrue(result is UpdateResult.Failed)
        assertTrue((result as UpdateResult.Failed).message.contains("9.0.0"))
    }

    @Test
    fun `version comparison handles prefix and suffix`() {
        assertTrue(UpdateChecker.compareVersions("v0.1.4", "0.1.3") > 0)
        assertEquals(0, UpdateChecker.compareVersions("0.1.3", "v0.1.3"))
        assertTrue(UpdateChecker.compareVersions("0.1.3", "0.1.10") < 0)
        assertTrue(UpdateChecker.compareVersions("1.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("0.2.0-rc1", "0.1.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("0.1", "0.1.0"))
    }

    @Test
    fun `parse release picks first apk asset`() {
        val release = UpdateChecker.parseRelease(releaseJson)
        assertEquals("v0.1.4", release?.tagName)
        assertEquals("https://example.com/app.apk", release?.apkUrl)
    }

    @Test
    fun `release notes are converted to numbered plain text`() {
        val body = """
            ## 新增

            - **回看与重刷**：进度页点任意轮次回看
            - 收藏：答题页可收藏

            ## 修复

            - 修复迁移崩溃
        """.trimIndent()
        val text = UpdateChecker.formatReleaseNotes(body)
        assertTrue(text.contains("1. 回看与重刷：进度页点任意轮次回看"))
        assertTrue(text.contains("2. 收藏：答题页可收藏"))
        assertTrue(text.contains("3. 修复迁移崩溃"))
        assertTrue(!text.contains("##"))
        assertTrue(!text.contains("**"))
        assertTrue(!text.contains("- "))
    }
}
