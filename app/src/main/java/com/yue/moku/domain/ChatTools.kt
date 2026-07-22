package com.yue.moku.domain

import com.yue.moku.data.KnowledgeEntity
import com.yue.moku.data.MessageEntity
import org.json.JSONArray
import org.json.JSONObject
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

    private fun isCjk(value: Int) =
        value in 0x3040..0x30FF ||   // Hiragana + Katakana
        value in 0x3400..0x9FFF ||   // CJK Unified + Extension A
        value in 0xF900..0xFAFF ||   // CJK Compatibility
        value in 0x20000..0x2A6DF || // CJK Extension B
        value in 0x2A700..0x2B73F || // CJK Extension C
        value in 0x2B740..0x2B81F || // CJK Extension D
        value in 0x2B820..0x2CEAF || // CJK Extension E
        value in 0x2CEB0..0x2EBEF || // CJK Extension F
        value in 0x30000..0x3134F    // CJK Extension G/H
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
        // CJK Unified (基本区 + 扩展A + 兼容区) + 日文假名 + CJK 扩展 B~G
        Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uf900-\\ufaff\\x{20000}-\\x{2a6df}\\x{2a700}-\\x{2b73f}\\x{2b740}-\\x{2b81f}\\x{2b820}-\\x{2ceaf}\\x{2ceb0}-\\x{2ebef}\\x{30000}-\\x{3134f}]+").findAll(lower).forEach { match ->
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
            if (used + cost > budget) break
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

/**
 * 判断用户当前消息是否构成"写入知识库"的明确指令。
 *
 * 设计原则：宁可漏判（漏判时 AI 拿不到 save_knowledge 工具，物理上无法写入），
 * 也不要误判（误判时 AI 可能借机把没授权的内容塞进知识库）。
 *
 * 因此这里用强白名单：必须出现明确的"动作 + 目标"组合才返回 true。
 */
object KnowledgeWriteIntent {

    /** 触发动作词：必须是"加入/记入/保存到/写进/存到/存进/加进/放进"等写入性动词 */
    private val writeActions = listOf(
        "加入", "记入", "存到", "存进", "保存到", "保存进", "写进", "写到",
        "记到", "记进", "加进", "放进", "录入", "收录到", "添加到", "添加进",
    )

    /** 写入目标：必须是"知识库"或"设定"。设定也属于知识库的一种调用类别，等同写入 */
    private val writeTargets = listOf("知识库", "设定库")

    /** 反歧义黑名单：如果命中说明不是写入指令，提前返回 false */
    private val ambiguousBlacklist = listOf(
        "读取知识库", "查询知识库", "看一下知识库", "看看知识库",
        "删除知识库", "清空知识库", "调出知识库", "不要写入知识库",
        "别写入知识库", "不要加入知识库",
        "读取设定库", "查询设定库", "删除设定库", "清空设定库",
        "不要写入设定库", "别写入设定库",
    )

    /**
     * @return true 仅当消息同时含 (动作词) + (目标词) 且未被黑名单覆盖。
     *          动作与目标之间允许出现最多 4 个字符（如"把 X 加到 知识库"）。
     */
    fun isWriteRequest(userText: String): Boolean {
        val text = userText.trim()
        if (text.isEmpty()) return false

        // 反歧义黑名单（包含变体如"读取/查询/不要"）
        for (pattern in ambiguousBlacklist) {
            if (text.contains(pattern)) return false
        }

        for (action in writeActions) {
            val actionIdx = text.indexOf(action)
            if (actionIdx < 0) continue
            for (target in writeTargets) {
                val targetIdx = text.indexOf(target, actionIdx + action.length)
                if (targetIdx < 0) continue
                val gap = targetIdx - (actionIdx + action.length)
                // 动作与目标之间最多间隔 4 个字符，否则视为无关短语
                if (gap in 0..4) return true
                // "把这条加到知识库"：把 X 加到 知识库 -> hit "加到"
                // 但 "加" 不在 writeActions 里——补一个独立分支覆盖"加到"
            }
        }

        // 兜底：常见的 "加到/放进知识库" 模式（带"到"字）
        val toPattern = Regex("(加到|放进?到|记到|写到|存到|保存到).{0,4}(知识库|设定库)")
        return toPattern.containsMatchIn(text)
    }

    /** 返回 save_knowledge 工具的 OpenAI function-calling schema */
    fun buildToolSchema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", "save_knowledge")
            put("description", "将一条信息保存到本地写作知识库。仅当用户在当前消息里明确要求将其记入/保存/加入知识库时才调用；不要凭用户未明示的内容自行触发。")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().apply { put("title"); put("content") })
                put("properties", JSONObject().apply {
                    put("title", JSONObject().apply {
                        put("type", "string")
                        put("description", "条目标题，简短标签，例如'小碳的性格'")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "条目内容，使用用户原话或最小精简版本，不要自行添补用户未给出的信息")
                    })
                    put("category", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            listOf("要求", "设定", "人物", "资料").forEach { put(it) }
                        })
                        put("description", "默认'设定'。要求=写作风格/规则、设定=世界观/条件、人物=角色、资料=参考材料")
                    })
                    put("tags", JSONObject().apply {
                        put("type", "string")
                        put("description", "可选，逗号分隔的标签")
                    })
                    put("isPinned", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "可选，是否每次固定调用，默认 false。仅当用户明确要求'每次都用'时设为 true")
                    })
                })
            })
        })
    }

    /** 当本轮携带工具时附加在 system prompt 后的引导语 */
    fun systemPromptHint(): String =
        "本轮用户提供了适合写入知识库的内容。请按以下规则使用 save_knowledge 工具：" +
            "1）仅在用户明确要求写入时调用；2）title 用简短标签（≤12 字），" +
            "content 用用户原话或最小精简，不要替用户编造或补全未给出的信息；" +
            "3）调用后用一句话向用户报告\"已保存：xxx\""
}
