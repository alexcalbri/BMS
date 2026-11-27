package com.ideasdeveloper.bms.app.core.ui

/**
 * Codificador de codigos QR en Kotlin puro (sin Compose ni dependencias): modo byte (UTF-8), nivel de correccion
 * M, version automatica de 1 a 20 (hasta unos 660 bytes) y la mascara de menor penalizacion de las 8.
 * [encode] devuelve la matriz cuadrada de modulos (true = oscuro) sin zona de silencio.
 */
object QrCode {
    const val MaxVersion = 20

    // Nivel M por version (indice version - 1): codigos de correccion por bloque y bloques de los grupos 1 y 2.
    private val EcPerBlock = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26)
    private val Group1Blocks = intArrayOf(1, 1, 1, 2, 2, 4, 4, 2, 3, 4, 1, 6, 8, 4, 5, 7, 10, 9, 3, 3)
    private val Group1Data = intArrayOf(16, 28, 44, 32, 43, 27, 31, 38, 36, 43, 50, 36, 37, 40, 41, 45, 46, 43, 44, 41)
    private val Group2Blocks = intArrayOf(0, 0, 0, 0, 0, 0, 0, 2, 2, 1, 4, 2, 1, 5, 5, 3, 1, 4, 11, 13)
    private val Group2Data = intArrayOf(0, 0, 0, 0, 0, 0, 0, 39, 37, 44, 51, 37, 38, 41, 42, 46, 47, 44, 45, 42)

    private fun dataCodewords(version: Int): Int =
        Group1Blocks[version - 1] * Group1Data[version - 1] + Group2Blocks[version - 1] * Group2Data[version - 1]

    /** Bytes UTF-8 que caben en [version] (nivel M, modo byte). */
    fun byteCapacity(version: Int): Int {
        val countBits = if (version < 10) 8 else 16
        return (dataCodewords(version) * 8 - 4 - countBits) / 8
    }

    /**
     * Codifica [text]. [forcedMask] (0..7) y [forcedVersion] (1..20) fijan la mascara y la version (para pruebas);
     * con -1 / 0 se eligen solas. Lanza IllegalArgumentException si el texto no cabe.
     */
    fun encode(text: String, forcedMask: Int = -1, forcedVersion: Int = 0): Array<BooleanArray> {
        val bytes = text.encodeToByteArray()
        var version = if (forcedVersion > 0) forcedVersion else 1
        while (version <= MaxVersion && byteCapacity(version) < bytes.size) {
            if (forcedVersion > 0) throw IllegalArgumentException("La version $forcedVersion no admite ${bytes.size} bytes.")
            version++
        }
        require(version <= MaxVersion) { "El texto es demasiado largo para un codigo QR (${bytes.size} bytes)." }
        require(forcedMask in -1..7) { "Mascara invalida: $forcedMask" }

        val codewords = interleave(version, dataBlocks(version, bytes))
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(version, modules, isFunction)
        placeData(codewords, modules, isFunction)

        var bestMask = forcedMask
        if (bestMask < 0) {
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask, modules, isFunction)
                drawFormatBits(mask, modules)
                val penalty = penalty(modules)
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    bestMask = mask
                }
                applyMask(mask, modules, isFunction) // la mascara se deshace al repetirla
            }
        }
        applyMask(bestMask, modules, isFunction)
        drawFormatBits(bestMask, modules)
        return modules
    }

    // Datos + terminador + relleno, repartidos en los bloques del nivel M y con su correccion Reed-Solomon.
    private fun dataBlocks(version: Int, bytes: ByteArray): List<ByteArray> {
        val bits = ArrayList<Boolean>()
        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add(((value shr i) and 1) == 1)
        }
        append(0b0100, 4)
        append(bytes.size, if (version < 10) 8 else 16)
        for (b in bytes) append(b.toInt() and 0xFF, 8)
        val capacityBits = dataCodewords(version) * 8
        append(0, minOf(4, capacityBits - bits.size))
        while (bits.size % 8 != 0) bits.add(false)
        var pad = 0xEC
        while (bits.size < capacityBits) {
            append(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        val data = ByteArray(bits.size / 8)
        for (i in bits.indices) if (bits[i]) data[i / 8] = (data[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()

        val blocks = ArrayList<ByteArray>()
        var offset = 0
        val g1 = Group1Blocks[version - 1]
        val total = g1 + Group2Blocks[version - 1]
        val generator = reedSolomonGenerator(EcPerBlock[version - 1])
        for (i in 0 until total) {
            val length = if (i < g1) Group1Data[version - 1] else Group2Data[version - 1]
            val block = data.copyOfRange(offset, offset + length)
            offset += length
            blocks.add(block + reedSolomonRemainder(block, generator))
        }
        return blocks
    }

    // Intercala por columnas los datos de los bloques y luego su correccion.
    private fun interleave(version: Int, blocks: List<ByteArray>): ByteArray {
        val ec = EcPerBlock[version - 1]
        val out = ArrayList<Byte>()
        val maxData = blocks.maxOf { it.size - ec }
        for (i in 0 until maxData) for (block in blocks) if (i < block.size - ec) out.add(block[i])
        for (i in 0 until ec) for (block in blocks) out.add(block[block.size - ec + i])
        return out.toByteArray()
    }

    // GF(256) con el polinomio 0x11D.
    private val ExpTable = IntArray(512)
    private val LogTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            ExpTable[i] = x
            LogTable[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) ExpTable[i] = ExpTable[i - 255]
    }

    private fun gfMultiply(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else ExpTable[LogTable[a] + LogTable[b]]

    // Coeficientes (sin el termino lider) de (x - a^0)(x - a^1)...(x - a^(degree-1)), de mayor a menor grado.
    private fun reedSolomonGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMultiply(poly[j], ExpTable[i])
            }
            poly = next
        }
        return poly.copyOfRange(1, poly.size)
    }

    private fun reedSolomonRemainder(data: ByteArray, generator: IntArray): ByteArray {
        val result = IntArray(generator.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMultiply(generator[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private fun set(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, row: Int, col: Int, dark: Boolean) {
        modules[row][col] = dark
        isFunction[row][col] = true
    }

    private fun drawFunctionPatterns(version: Int, modules: Array<BooleanArray>, isFunction: Array<BooleanArray>) {
        val size = modules.size
        // Lineas de sincronizacion.
        for (i in 0 until size) {
            set(modules, isFunction, 6, i, i % 2 == 0)
            set(modules, isFunction, i, 6, i % 2 == 0)
        }
        // Tres buscadores con su separador.
        fun finder(cr: Int, cc: Int) {
            for (dr in -4..4) for (dc in -4..4) {
                val r = cr + dr
                val c = cc + dc
                if (r !in 0 until size || c !in 0 until size) continue
                val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                set(modules, isFunction, r, c, dist != 2 && dist != 4)
            }
        }
        finder(3, 3)
        finder(3, size - 4)
        finder(size - 4, 3)
        // Patrones de alineacion.
        val positions = alignmentPositions(version)
        for (i in positions.indices) for (j in positions.indices) {
            val skip = (i == 0 && j == 0) || (i == 0 && j == positions.size - 1) || (i == positions.size - 1 && j == 0)
            if (skip) continue
            for (dr in -2..2) for (dc in -2..2) {
                val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                set(modules, isFunction, positions[i] + dr, positions[j] + dc, dist != 1)
            }
        }
        // Reserva de la informacion de formato (se escribe al elegir mascara) y modulo oscuro fijo.
        for (i in 0..8) {
            isFunction[8][i] = true
            isFunction[i][8] = true
        }
        for (i in 0 until 8) {
            isFunction[8][size - 1 - i] = true
            isFunction[size - 1 - i][8] = true
        }
        set(modules, isFunction, size - 8, 8, true)
        // Informacion de version (version 7 o mayor): BCH(18,6).
        if (version >= 7) {
            var rem = version
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = ((bits shr i) and 1) == 1
                val a = size - 11 + i % 3
                val b = i / 3
                set(modules, isFunction, b, a, dark)
                set(modules, isFunction, a, b, dark)
            }
        }
    }

    private fun alignmentPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val size = version * 4 + 17
        val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
        val result = IntArray(count)
        result[0] = 6
        for (i in 1 until count) result[i] = size - 7 - (count - 1 - i) * step
        return result
    }

    // Recorre la matriz en zigzag de dos columnas, de abajo arriba y de derecha a izquierda, saltando la columna 6.
    private fun placeData(codewords: ByteArray, modules: Array<BooleanArray>, isFunction: Array<BooleanArray>) {
        val size = modules.size
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val col = right - j
                    val upward = ((right + 1) and 2) == 0
                    val row = if (upward) size - 1 - vert else vert
                    if (!isFunction[row][col] && bitIndex < codewords.size * 8) {
                        modules[row][col] = ((codewords[bitIndex ushr 3].toInt() shr (7 - (bitIndex and 7))) and 1) == 1
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
    }

    private fun maskBit(mask: Int, row: Int, col: Int): Boolean = when (mask) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> row * col % 2 + row * col % 3 == 0
        6 -> (row * col % 2 + row * col % 3) % 2 == 0
        else -> ((row + col) % 2 + row * col % 3) % 2 == 0
    }

    private fun applyMask(mask: Int, modules: Array<BooleanArray>, isFunction: Array<BooleanArray>) {
        for (r in modules.indices) for (c in modules.indices) {
            if (!isFunction[r][c] && maskBit(mask, r, c)) modules[r][c] = !modules[r][c]
        }
    }

    // Formato: nivel M (00) + mascara, BCH(15,5) y mascara XOR 0x5412, escrito en las dos copias.
    private fun drawFormatBits(mask: Int, modules: Array<BooleanArray>) {
        val size = modules.size
        val data = mask // nivel M = 0b00 en los dos bits altos
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = ((bits shr i) and 1) == 1
        for (i in 0..5) modules[i][8] = bit(i)
        modules[7][8] = bit(6)
        modules[8][8] = bit(7)
        modules[8][7] = bit(8)
        for (i in 9..14) modules[8][14 - i] = bit(i)
        for (i in 0..7) modules[8][size - 1 - i] = bit(i)
        for (i in 8..14) modules[size - 15 + i][8] = bit(i)
        modules[size - 8][8] = true
    }

    // Penalizacion total de las cuatro reglas del estandar.
    private fun penalty(modules: Array<BooleanArray>): Int {
        val size = modules.size
        var result = 0
        // Regla 1: tiradas de cinco o mas modulos iguales en filas y columnas.
        for (line in 0 until 2 * size) {
            val horizontal = line < size
            val index = if (horizontal) line else line - size
            var runColor = false
            var runLength = 0
            for (i in 0 until size) {
                val color = if (horizontal) modules[index][i] else modules[i][index]
                if (i > 0 && color == runColor) {
                    runLength++
                    if (runLength == 5) result += 3 else if (runLength > 5) result++
                } else {
                    runColor = color
                    runLength = 1
                }
            }
        }
        // Regla 2: bloques de 2x2 del mismo color.
        for (r in 0 until size - 1) for (c in 0 until size - 1) {
            val color = modules[r][c]
            if (color == modules[r][c + 1] && color == modules[r + 1][c] && color == modules[r + 1][c + 1]) result += 3
        }
        // Regla 3: patron 1:1:3:1:1 con cuatro modulos claros a un lado.
        val pattern = booleanArrayOf(true, false, true, true, true, false, true)
        for (line in 0 until 2 * size) {
            val horizontal = line < size
            val index = if (horizontal) line else line - size
            fun at(i: Int): Boolean = if (i < 0 || i >= size) false else if (horizontal) modules[index][i] else modules[i][index]
            for (start in -4..size - 7) {
                var core = true
                for (k in 0 until 7) if (at(start + k) != pattern[k]) { core = false; break }
                if (!core) continue
                // Fuera de la matriz cuenta como claro (como la zona de silencio).
                val before = (1..4).all { !at(start - it) }
                val after = (7..10).all { !at(start + it) }
                if (before) result += 40
                if (after) result += 40
            }
        }
        // Regla 4: proporcion de modulos oscuros alejada del 50 %.
        var dark = 0
        for (row in modules) for (cell in row) if (cell) dark++
        val total = size * size
        result += ((kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10
        return result
    }
}
