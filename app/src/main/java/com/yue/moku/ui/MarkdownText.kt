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
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

@Composable
fun rememberMarkwon(context: Context): Markwon = remember(context) {
    Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .build()
}

fun Markwon.render(markdown: String): AnnotatedString {
    val spanned: Spanned = this.toMarkdown(markdown)
    return buildAnnotatedString {
        append(spanned.toString())
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            when (span) {
                is StyleSpan -> {
                    val s = SpanStyle(
                        fontWeight = if (span.style and android.graphics.Typeface.BOLD != 0) FontWeight.Bold else null,
                        fontStyle = if (span.style and android.graphics.Typeface.ITALIC != 0) FontStyle.Italic else null,
                    )
                    if (s.fontWeight != null || s.fontStyle != null) addStyle(s, start, end)
                }
                is TypefaceSpan -> when (span.family) {
                    "monospace" -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)
                    "serif" -> addStyle(SpanStyle(fontFamily = FontFamily.Serif), start, end)
                    else -> addStyle(SpanStyle(fontFamily = FontFamily.SansSerif), start, end)
                }
                is ForegroundColorSpan -> addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
                is BackgroundColorSpan -> addStyle(SpanStyle(background = Color(span.backgroundColor)), start, end)
                is AbsoluteSizeSpan -> addStyle(SpanStyle(fontSize = (if (span.dip) span.size.dp.value else span.size.toFloat()).sp), start, end)
                is StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is URLSpan -> addStringAnnotation("URL", span.url ?: "", start, end)
            }
        }
    }
}
