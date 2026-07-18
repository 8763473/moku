package com.yue.moku

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestContractTest {
    @Test
    fun `main activity resizes instead of panning when keyboard opens`() {
        val manifest = sequenceOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile) ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")

        val source = manifest.readText()
        val mainActivity = Regex(
            """<activity[^>]*android:name="\.MainActivity"[^>]*>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(source)?.value.orEmpty()

        assertTrue(
            "MainActivity must use adjustResize so the IME does not pan the whole Compose UI",
            mainActivity.contains("android:windowSoftInputMode=\"adjustResize\""),
        )
    }
}
