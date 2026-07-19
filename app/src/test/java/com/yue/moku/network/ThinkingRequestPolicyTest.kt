package com.yue.moku.network

import com.yue.moku.domain.ApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingRequestPolicyTest {
    @Test
    fun `disabled thinking requests hard disable without changing user prompt`() {
        val messages = listOf(ApiMessage("user", "写一段开场白"))
        val plan = planChatRequest(thinkingMode = false, messages = messages)

        assertEquals("none", plan.reasoningEffort)
        assertEquals(false, plan.enableThinking)
        assertEquals(messages, plan.messages)
    }

    @Test
    fun `enabled thinking leaves provider defaults untouched`() {
        val messages = listOf(ApiMessage("user", "分析人物动机"))
        val plan = planChatRequest(thinkingMode = true, messages = messages)

        assertNull(plan.reasoningEffort)
        assertNull(plan.enableThinking)
        assertEquals(messages, plan.messages)
    }
}
