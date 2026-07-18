package com.yue.moku

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootImeLayoutContractTest {
    @Test
    fun `bottom navigation leaves layout while keyboard is visible`() {
        val sourceFile = sequenceOf(
            File("app/src/main/java/com/yue/moku/ui/MoKuApp.kt"),
            File("src/main/java/com/yue/moku/ui/MoKuApp.kt"),
            File("../app/src/main/java/com/yue/moku/ui/MoKuApp.kt"),
        ).firstOrNull(File::isFile) ?: error("MoKuApp.kt not found from ${File(".").absolutePath}")

        val source = sourceFile.readText()
        assertTrue(
            "Root layout must observe the IME so it can stop reserving navigation-bar height",
            source.contains("WindowInsets.ime.getBottom"),
        )
        assertTrue(
            "NavigationBar must not participate in layout while the IME is visible",
            Regex("""if\s*\(!imeVisible\)\s*\{?\s*NavigationBar""").containsMatchIn(source),
        )
        assertTrue(
            "Root Scaffold must not add a navigation-bar inset after its bottom bar leaves",
            source.contains("contentWindowInsets = WindowInsets(0, 0, 0, 0)"),
        )

        val chatSource = File(sourceFile.parentFile, "ChatScreen.kt").readText()
        assertTrue(
            "Chat Scaffold must let imePadding place the composer directly against the keyboard",
            chatSource.contains("contentWindowInsets = WindowInsets(0, 0, 0, 0)"),
        )
    }
}
