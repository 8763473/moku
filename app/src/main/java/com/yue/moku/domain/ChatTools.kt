package com.yue.moku.domain

import com.yue.moku.data.KnowledgeEntity
import com.yue.moku.data.MessageEntity
import java.util.Locale
import kotlin.math.ceil

data class ParsedAnswer(val reasoning: String, val content: String)

object ThinkParser {
    private val completeBlock = Regex("<think>(.*?)</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val opening = Regex("<think>", RegexOption.IGNORE_CASE)

    fun parse(rawContent: String, explicitReasoning: String = ""): ParsedAnswer {
        val captured = completeBlock.findAll(rawContent).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        var visible = completeBlock.replace(rawContent, "").trim()
        var unfinished = ""
        val open = opening.find(visible)
        if (open != null) {
            unfinished = visible.substring(open.range.last + 1).trim()
            visible = visible.substring(0, open.range.first).trim()
        }
        val reasoning = (listOf(explicitReasoning.trim()) + captured + unfinished)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return ParsedAnswer(reasoning = reasoning, content = visible)
    }
}

object TokenEstimator {
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var ascii = 0
        var other = 0
        text.codePoints().forEach { codePoint ->
            when {
                isCjk(codePoint) -> cjk++
                codePoint <= 0x7f -> ascii++
                else -> other++
            }
        }
        return cjk + other + ceil(ascii / 4.0).toInt()
    }

    private fun isCjk(value: Int) = value in 0x3400..0x9FFF || value in 0xF900..0xFAFF
}

data class RetrievedKnowledge(val item: KnowledgeEntity, val score: Double)

object KnowledgeRetriever {
    fun retrieve(query: String, entries: List<KnowledgeEntity>, limit: Int): List<RetrievedKnowledge> {
        val queryTokens = tokens(query)
        return entries.asSequence()
            .filter { it.isEnabled }
            .map { entry ->
                val bodyOverlap = queryTokens.intersect(tokens(entry.content)).size
                val titleOverlap = queryTokens.intersect(tokens(entry.title)).size
                val tagOverlap = queryTokens.intersect(tokens(entry.tags)).size
                val score = bodyOverlap * 8.0 + titleOverlap * 14.0 + tagOverlap * 12.0 +
                    entry.priority * 1.5 + if (entry.isPinned) 1_000.0 else 0.0
                RetrievedKnowledge(entry, score)
            }
            .filter { it.item.isPinned || it.score > it.item.priority * 1.5 }
            .sortedWith(compareByDescending<RetrievedKnowledge> { it.score }.thenByDescending { it.item.updatedAt })
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    fun toPrompt(items: List<RetrievedKnowledge>): String {
        if (items.isEmpty()) return ""
        return buildString {
            appendLine("[本次已调用的写作知识库]")
            appendLine("以下内容是用户保存的长期资料与要求。要求类内容必须遵守；资料类内容仅在相关时使用，不要凭空扩写。")
            items.forEachIndexed { index, match ->
                val item = match.item
                appendLine("${index + 1}. [${item.category}] ${item.title}")
                appendLine(item.content.trim())
                if (item.tags.isNotBlank()) appendLine("标签：${item.tags}")
            }
        }.trim()
    }

    internal fun tokens(value: String): Set<String> {
        val lower = value.lowercase()
        val result = Regex("[a-z0-9_]{2,}").findAll(lower).map { it.value }.toMutableSet()
        Regex("[\\u3400-\\u9fff]+").findAll(lower).forEach { match ->
            val text = match.value
            if (text.length == 1) result += text
            else for (index in 0 until text.length - 1) result += text.substring(index, index + 2)
        }
        return result
    }
}

data class ApiMessage(val role: String, val content: String)

object ContextBuilder {
    data class Result(
        val messages: List<ApiMessage>,
        val estimatedPromptTokens: Int,
        val systemTokens: Int,
        val memoryTokens: Int,
        val historyTokens: Int,
        val historyMessageCount: Int,
    )

    fun build(systemPrompt: String, memoryPrompt: String, history: List<MessageEntity>, contextWindow: Int): Result {
        val systemRaw = systemPrompt.trim()
        val memoryRaw = memoryPrompt.trim()
        val systemTokens = TokenEstimator.estimate(systemRaw)
        val memoryTokens = TokenEstimator.estimate(memoryRaw)
        val reserve = maxOf(1_024, (contextWindow * 0.15).toInt())
        val budget = (contextWindow - reserve).coerceAtLeast(512)
        var used = systemTokens + memoryTokens + 8
        val selected = mutableListOf<MessageEntity>()
        for (message in history.asReversed()) {
            val cost = TokenEstimator.estimate(message.content) + 6
            if (selected.isNotEmpty() && used + cost > budget) break
            selected += message
            used += cost
        }
        val system = listOf(systemRaw, memoryRaw).filter { it.isNotEmpty() }.joinToString("\n\n")
        val result = buildList {
            if (system.isNotBlank()) add(ApiMessage("system", system))
            selected.asReversed().forEach { message ->
                add(ApiMessage(message.role, message.content))
            }
        }
        val historyTokens = used - systemTokens - memoryTokens - 8
        return Result(result, used, systemTokens, memoryTokens, historyTokens, selected.size)
    }
}

fun formatTokens(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000f)
    value >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", value / 1_000f)
    else -> value.toString()
}
