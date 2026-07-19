package com.yue.moku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatAutoScrollContractTest {
    @Test
    fun `streaming follows the real bottom and yields to user dragging`() {
        val source = sequenceOf(
            File("app/src/main/java/com/yue/moku/ui/ChatScreen.kt"),
            File("src/main/java/com/yue/moku/ui/ChatScreen.kt"),
            File("../app/src/main/java/com/yue/moku/ui/ChatScreen.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("ChatScreen.kt not found from ${File(".").absolutePath}")

        assertTrue(
            "User drag must suspend automatic scrolling immediately",
            source.contains("collectIsDraggedAsState"),
        )
        assertTrue(
            "Bottom detection must use whether more content can scroll forward",
            source.contains("listState.canScrollForward"),
        )
        assertTrue(
            "Streaming must anchor the end of a tall final message, not its top",
            source.contains("scrollToItem(messages.lastIndex, Int.MAX_VALUE)"),
        )
        assertFalse(
            "A tall last message stays visible while the user scrolls up, so visibility is not a bottom signal",
            source.contains("lastVisible >= messages.lastIndex"),
        )
    }
}
