package com.yue.moku.ui

import android.content.Context
import android.text.Spanned
import android.text.style.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.noties.markwon.Markwon
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

@Composable
fun rememberMarkwon(context: Context): Markwon = remember(context) {
    Markwon.builder(context)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .build()
}

/**
 * 将 Markdown 渲染为 Compose [AnnotatedString]。
 * [linkColor] 用于 URL 链接的着色；由调用方从 Composable 上下文中传入。
 * 默认透明表示未指定时使用平台默认链接色（与 Markwon 内部行为一致）。
 */
fun Markwon.render(
    markdown: String,
    linkColor: Color = Color.Unspecified,
): AnnotatedString {
    val spanned = this.toMarkdown(markdown)
    return buildAnnotatedString {
        // Copy all text first, then apply spans in order of their start positions.
        append(spanned.toString())

        // Collect all spans and sort by start position to handle nested formatting correctly.
        // Use System.identityHashCode for dedup to avoid hashCode collision silently dropping a span.
        val spansList = (0 until spanned.length).flatMap { i ->
            spanned.getSpans(i, i + 1, Any::class.java)
                .filter { spanned.getSpanEnd(it) > spanned.getSpanStart(it) }
                .distinctBy { System.identityHashCode(it) }
        }.sortedBy { spanned.getSpanStart(it) }

        for (span in spansList) {
            val start = spanned.getSpanStart(span).coerceIn(0, spanned.length)
            val end = spanned.getSpanEnd(span).coerceIn(start, spanned.length)
            when (span) {
                is StyleSpan -> {
                    val fontWeight = if (span.style and android.graphics.Typeface.BOLD != 0) FontWeight.Bold else null
                    val fontStyle = if (span.style and android.graphics.Typeface.ITALIC != 0) FontStyle.Italic else null
                    addStyle(SpanStyle(fontWeight = fontWeight, fontStyle = fontStyle), start, end)
                }
                is TypefaceSpan -> {
                    when (span.family) {
                        "monospace" -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)
                        "serif" -> addStyle(SpanStyle(fontFamily = FontFamily.Serif), start, end)
                        else -> Unit // Use default font family
                    }
                }
                is ForegroundColorSpan -> addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
                is BackgroundColorSpan -> addStyle(SpanStyle(background = Color(span.backgroundColor)), start, end)
                is AbsoluteSizeSpan -> {
                    val sizeSp = if (span.dip) span.size.dp.value else span.size.toFloat()
                    addStyle(SpanStyle(fontSize = sizeSp.sp), start, end)
                }
                is StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is URLSpan -> {
                    val url = span.url ?: ""
                    addStringAnnotation("URL", url, start, end)
                    if (linkColor != Color.Unspecified) {
                        addStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                            start,
                            end,
                        )
                    }
                }
            }
        }
    }
}
