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

    // ------- KnowledgeWriteIntent -------

    @Test
    fun `write intent matches explicit save-to-knowledge phrasing`() {
        assertTrue(KnowledgeWriteIntent.isWriteRequest("把这条加到知识库：小碳的性格是温和但固执"))
        assertTrue(KnowledgeWriteIntent.isWriteRequest("以下设定请记入知识库：林舟，28 岁，机修工"))
        assertTrue(KnowledgeWriteIntent.isWriteRequest("存到知识库：王城在北方平原上"))
        assertTrue(KnowledgeWriteIntent.isWriteRequest("把人物简介写进知识库"))
        assertTrue(KnowledgeWriteIntent.isWriteRequest("加进知识库吧"))
        assertTrue(KnowledgeWriteIntent.isWriteRequest("建议存进知识库"))
    }

    @Test
    fun `write intent rejects normal conversation`() {
        assertFalse(KnowledgeWriteIntent.isWriteRequest("续写林舟修理机器的场景"))
        assertFalse(KnowledgeWriteIntent.isWriteRequest("帮我把这一段润色一下"))
        assertFalse(KnowledgeWriteIntent.isWriteRequest("知识库里说的小碳是乐观派对吧？"))
        assertFalse(KnowledgeWriteIntent.isWriteRequest(""))
    }

    @Test
    fun `write intent rejects anti-patterns and read-only queries`() {
        assertFalse(KnowledgeWriteIntent.isWriteRequest("请读取知识库的人物设定"))
        assertFalse(KnowledgeWriteIntent.isWriteRequest("查询知识库"))
        assertFalse(KnowledgeWriteIntent.isWriteRequest("删除知识库中过期的条目"))
        assertFalse(KnowledgeWriteIntent.isWriteRequest("不要写入知识库"))
    }

    @Test
    fun `save_knowledge tool schema has required fields`() {
        val schema = KnowledgeWriteIntent.buildToolSchema()
        val function = schema.getJSONObject("function")
        assertEquals("save_knowledge", function.getString("name"))
        val params = function.getJSONObject("parameters")
        val required = params.getJSONArray("required")
        assertEquals("title", required.getString(0))
        assertEquals("content", required.getString(1))
        // properties 必须含 title/content/category/tags/isPinned
        val props = params.getJSONObject("properties")
        listOf("title", "content", "category", "tags", "isPinned").forEach {
            assertTrue("missing property: $it", props.has(it))
        }
        // category enum 包含 4 个分类
        val categories = props.getJSONObject("category").getJSONArray("enum")
        assertEquals(4, categories.length())
    }
}

