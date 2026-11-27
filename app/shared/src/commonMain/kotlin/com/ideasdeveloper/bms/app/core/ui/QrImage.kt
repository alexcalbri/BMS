package com.ideasdeveloper.bms.app.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dibuja [text] como codigo QR: modulos negros sobre fondo BLANCO con zona de silencio de 4 modulos, sin importar el
 * tema (en modo oscuro tampoco se invierte, o los lectores no lo leerian). No dibuja nada si el texto no cabe.
 */
@Composable
fun QrImage(text: String, contentDescription: String, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val matrix = remember(text) { runCatching { QrCode.encode(text) }.getOrNull() } ?: return
    val quiet = 4
    val total = matrix.size + quiet * 2
    Canvas(modifier = modifier.size(size).semantics { this.contentDescription = contentDescription }) {
        drawRect(Color.White, Offset.Zero, this.size)
        val cell = this.size.width / total
        for (row in matrix.indices) for (col in matrix.indices) {
            if (matrix[row][col]) {
                // Un poco de solape evita lineas claras entre modulos por el redondeo de pixeles.
                drawRect(Color.Black, Offset((col + quiet) * cell, (row + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
