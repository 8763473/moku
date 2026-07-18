package com.yue.moku.domain

import com.yue.moku.data.KnowledgeEntity
import com.yue.moku.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolsTest {
    @Test
    fun `think parser separates complete think block`() {
        val parsed = ThinkParser.parse("<think>先检查人物动机</think>\n正文开始")

        assertEquals("先检查人物动机", parsed.reasoning)
        assertEquals("正文开始", parsed.content)
    }

    @Test
    fun `think parser exposes unfinished streamed reasoning`() {
        val parsed = ThinkParser.parse("<think>正在推演下一段")

        assertEquals("正在推演下一段", parsed.reasoning)
        assertEquals("", parsed.content)
    }

    @Test
    fun `explicit reasoning and visible content stay separate`() {
        val parsed = ThinkParser.parse("这是正文", "这是接口返回的推理")

        assertEquals("这是接口返回的推理", parsed.reasoning)
        assertEquals("这是正文", parsed.content)
    }

    @Test
    fun `token estimator handles Chinese more densely than ascii`() {
        assertEquals(4, TokenEstimator.estimate("中文写作"))
        assertEquals(1, TokenEstimator.estimate("test"))
    }

    @Test
    fun `retriever always includes pinned requirement and finds relevant setting`() {
        val pinned = KnowledgeEntity(id = 1, title = "全局文风", content = "避免网络流行语", category = "要求", isPinned = true)
        val relevant = KnowledgeEntity(id = 2, title = "林舟的人物设定", content = "林舟害怕深水，但擅长修理机械", tags = "林舟,人物")
        val irrelevant = KnowledgeEntity(id = 3, title = "王城地图", content = "王城位于北方平原", tags = "地图")

        val result = KnowledgeRetriever.retrieve("续写林舟修理机器的场景", listOf(irrelevant, relevant, pinned), 5)

        assertTrue(result.any { it.item.id == pinned.id })
        assertTrue(result.any { it.item.id == relevant.id })
        assertFalse(result.any { it.item.id == irrelevant.id })
    }

    @Test
    fun `context builder keeps latest message when budget is tight`() {
        val history = (1..12).map { index ->
            MessageEntity(
                id = index.toLong(),
                conversationId = 1,
                role = if (index % 2 == 0) "assistant" else "user",
                content = "段落".repeat(180),
            )
        }

        val result = ContextBuilder.build("系统", "", history, 1_024)

        assertEquals(history.last().content, result.messages.last().content)
        assertTrue(result.messages.size < history.size + 1)
    }
}

