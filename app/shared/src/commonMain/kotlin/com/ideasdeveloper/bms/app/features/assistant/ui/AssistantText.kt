package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.i18n.AppLanguage
import com.ideasdeveloper.bms.app.core.theme.AppTheme

// Texto del asistente con el formato minimo de Markdown que suele usar: parrafos, listas, encabezados, bloques de codigo,
// `codigo` y **negrita** en linea. No agrega ninguna dependencia: lo que no se reconoce se muestra tal cual.

private sealed interface TextBlock {
    data class Paragraph(val text: String) : TextBlock
    data class Heading(val text: String) : TextBlock
    data class Bullet(val marker: String, val text: String) : TextBlock
    data class Code(val text: String) : TextBlock
}

private val BulletMarkers = listOf("- ", "* ", "+ ")

private fun orderedMarker(line: String): String? {
    var index = 0
    while (index < line.length && line[index].isDigit()) index++
    if (index == 0 || index > 3 || index + 1 >= line.length) return null
    if (line[index] != '.' && line[index] != ')') return null
    if (line[index + 1] != ' ') return null
    return line.substring(0, index + 1)
}

private fun parseBlocks(text: String): List<TextBlock> {
    val blocks = mutableListOf<TextBlock>()
    val paragraph = mutableListOf<String>()
    var code: MutableList<String>? = null

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(TextBlock.Paragraph(paragraph.joinToString("\n")))
            paragraph.clear()
        }
    }

    for (raw in text.replace("\r\n", "\n").split('\n')) {
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        val open = code
        if (open != null) {
            if (trimmed.startsWith("```")) {
                blocks.add(TextBlock.Code(open.joinToString("\n")))
                code = null
            } else {
                open.add(raw)
            }
            continue
        }
        if (trimmed.startsWith("```")) {
            flushParagraph()
            code = mutableListOf()
            continue
        }
        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }
        if (trimmed.startsWith("#")) {
            val heading = trimmed.trimStart('#')
            if (heading.startsWith(" ")) {
                flushParagraph()
                blocks.add(TextBlock.Heading(heading.trim()))
                continue
            }
        }
        val bullet = BulletMarkers.firstOrNull { trimmed.startsWith(it) }
        if (bullet != null) {
            flushParagraph()
            blocks.add(TextBlock.Bullet("•", trimmed.removePrefix(bullet).trim()))
            continue
        }
        val ordered = orderedMarker(trimmed)
        if (ordered != null) {
            flushParagraph()
            blocks.add(TextBlock.Bullet(ordered, trimmed.removePrefix(ordered).trim()))
            continue
        }
        paragraph.add(line)
    }
    // Un bloque de codigo sin cerrar (la respuesta aun se esta recibiendo) se muestra con lo recibido hasta ahora.
    code?.let { blocks.add(TextBlock.Code(it.joinToString("\n"))) }
    flushParagraph()
    return blocks
}

// `codigo` y **negrita** en linea; un marcador sin cierre se escribe tal cual.
internal fun inlineText(text: String, codeBackground: Color, codeColor: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        if (text.startsWith("**", index)) {
            val end = text.indexOf("**", index + 2)
            if (end > index + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(index + 2, end)) }
                index = end + 2
                continue
            }
        }
        if (text[index] == '`') {
            val end = text.indexOf('`', index + 1)
            if (end > index + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, color = codeColor)) {
                    append(text.substring(index + 1, end))
                }
                index = end + 1
                continue
            }
        }
        append(text[index])
        index++
    }
}

@Composable
// Respuesta del asistente: seleccionable para poder copiarla.
internal fun AssistantMarkdown(text: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val blocks = remember(text) { parseBlocks(text) }
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is TextBlock.Paragraph -> Text(
                        inlineText(block.text, colors.surfaceAlt, colors.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                    )
                    is TextBlock.Heading -> Text(
                        inlineText(block.text, colors.surfaceAlt, colors.text),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.text,
                    )
                    is TextBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text(block.marker, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            inlineText(block.text, colors.surfaceAlt, colors.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    is TextBlock.Code -> Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.text,
                        modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt, MaterialTheme.shapes.small).padding(8.dp),
                    )
                }
            }
        }
    }
}

// 1234567 -> "1.234.567" (espanol) o "1,234,567" (ingles).
internal fun tokensText(value: Long): String {
    val separator = if (AppLanguage.current == "en") ',' else '.'
    val digits = if (value < 0) (-value).toString() else value.toString()
    val grouped = StringBuilder()
    digits.forEachIndexed { index, char ->
        if (index > 0 && (digits.length - index) % 3 == 0) grouped.append(separator)
        grouped.append(char)
    }
    return if (value < 0) "-$grouped" else grouped.toString()
}
