package com.example.compiler

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Compiles res/values/strings.xml and res/values/colors.xml into:
 * 1) R.java source code
 * 2) Genuine binary resources.arsc table (ResTable_header + ResTable_package)
 * 3) Resource name-to-ID map (e.g. "string/app_name" -> 0x7f010000)
 */
object ResourceTableCompiler {

    private const val RES_TABLE_TYPE: Short = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE: Short = 0x0200
    private const val RES_TABLE_TYPE_TYPE: Short = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE: Short = 0x0202

    private const val TYPE_STRING: Byte = 0x03
    private const val TYPE_INT_COLOR_ARGB8: Byte = 0x1c

    data class CompiledResources(
        val rJavaSource: String,
        val resourcesArsc: ByteArray,
        val resourceIds: Map<String, Int>,
        val stringCount: Int,
        val colorCount: Int,
    )

    private data class StringEntry(val name: String, val value: String)
    private data class ColorEntry(val name: String, val argb: Int)

    fun compile(
        packageName: String,
        stringsXml: String?,
        colorsXml: String?,
    ): CompiledResources {
        val strings = parseStrings(stringsXml).ifEmpty {
            listOf(StringEntry("app_name", "App"))
        }
        val colors = parseColors(colorsXml)

        val resourceIds = linkedMapOf<String, Int>()
        val rBuilder = StringBuilder().apply {
            appendLine("package $packageName;")
            appendLine()
            appendLine("public final class R {")
            appendLine("    public static final class string {")
            strings.forEachIndexed { idx, entry ->
                val id = 0x7f010000 or idx
                resourceIds["string/${entry.name}"] = id
                appendLine("        public static final int ${sanitizeJavaId(entry.name)} = 0x${id.toString(16)};")
            }
            appendLine("    }")
            if (colors.isNotEmpty()) {
                appendLine("    public static final class color {")
                colors.forEachIndexed { idx, entry ->
                    val id = 0x7f020000 or idx
                    resourceIds["color/${entry.name}"] = id
                    appendLine("        public static final int ${sanitizeJavaId(entry.name)} = 0x${id.toString(16)};")
                }
                appendLine("    }")
            }
            appendLine("}")
        }

        val arscBytes = encodeResourcesArsc(packageName, strings, colors)
        return CompiledResources(
            rJavaSource = rBuilder.toString(),
            resourcesArsc = arscBytes,
            resourceIds = resourceIds,
            stringCount = strings.size,
            colorCount = colors.size,
        )
    }

    private fun parseStrings(xml: String?): List<StringEntry> {
        if (xml.isNullOrBlank()) return emptyList()
        val list = mutableListOf<StringEntry>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml.trim())))
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name").trim()
            val text = el.textContent.orEmpty()
            if (name.isNotEmpty()) {
                list.add(StringEntry(name, text))
            }
        }
        return list
    }

    private fun parseColors(xml: String?): List<ColorEntry> {
        if (xml.isNullOrBlank()) return emptyList()
        val list = mutableListOf<ColorEntry>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml.trim())))
        val nodes = doc.getElementsByTagName("color")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name").trim()
            val text = el.textContent.orEmpty().trim()
            if (name.isNotEmpty()) {
                parseHexColor(text)?.let { list.add(ColorEntry(name, it)) }
            }
        }
        return list
    }

    private fun parseHexColor(raw: String): Int? {
        if (!raw.startsWith("#")) return null
        val hex = raw.removePrefix("#")
        return when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    }

    private fun sanitizeJavaId(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun encodeResourcesArsc(
        packageName: String,
        strings: List<StringEntry>,
        colors: List<ColorEntry>,
    ): ByteArray {
        val globalStrings = strings.map { it.value }
        val globalPool = BinaryXmlEncoder.encodeUtf8StringPool(globalStrings)

        val typeNames = buildList {
            add("string")
            if (colors.isNotEmpty()) add("color")
        }
        val typeStringPool = BinaryXmlEncoder.encodeUtf8StringPool(typeNames)

        val keyNames = buildList {
            strings.forEach { add(it.name) }
            colors.forEach { add(it.name) }
        }
        val keyStringPool = BinaryXmlEncoder.encodeUtf8StringPool(keyNames)
        val keyIndexMap = keyNames.withIndex().associate { (i, name) -> name to i }

        val typeChunks = mutableListOf<ByteArray>()
        typeChunks.add(
            encodeTypeSpec(typeId = 1, entryCount = strings.size),
        )
        typeChunks.add(
            encodeTypeChunk(
                typeId = 1,
                entries = strings.mapIndexed { idx, entry ->
                    EncodedEntry(
                        keyIndex = keyIndexMap.getValue(entry.name),
                        dataType = TYPE_STRING,
                        data = idx,
                    )
                },
            ),
        )

        if (colors.isNotEmpty()) {
            typeChunks.add(encodeTypeSpec(typeId = 2, entryCount = colors.size))
            typeChunks.add(
                encodeTypeChunk(
                    typeId = 2,
                    entries = colors.map { entry ->
                        EncodedEntry(
                            keyIndex = keyIndexMap.getValue(entry.name),
                            dataType = TYPE_INT_COLOR_ARGB8,
                            data = entry.argb,
                        )
                    },
                ),
            )
        }

        val typesTotalSize = typeChunks.sumOf { it.size }
        val pkgHeaderSize = 288
        val keyStringsOffset = pkgHeaderSize + typeStringPool.size
        val pkgSize = pkgHeaderSize + typeStringPool.size + keyStringPool.size + typesTotalSize

        val pkgBuf = ByteBuffer.allocate(pkgSize).order(ByteOrder.LITTLE_ENDIAN)
        pkgBuf.putShort(RES_TABLE_PACKAGE_TYPE)
        pkgBuf.putShort(pkgHeaderSize.toShort())
        pkgBuf.putInt(pkgSize)
        pkgBuf.putInt(0x7F) // packageId

        // 128 UTF-16LE chars (256 bytes)
        val pkgChars = packageName.take(127)
        for (i in 0 until 128) {
            val ch = if (i < pkgChars.length) pkgChars[i].code else 0
            pkgBuf.putShort(ch.toShort())
        }
        pkgBuf.putInt(pkgHeaderSize) // typeStrings offset
        pkgBuf.putInt(typeNames.size) // lastPublicType
        pkgBuf.putInt(keyStringsOffset) // keyStrings offset
        pkgBuf.putInt(keyNames.size) // lastPublicKey
        pkgBuf.putInt(0) // typeIdOffset

        pkgBuf.put(typeStringPool)
        pkgBuf.put(keyStringPool)
        typeChunks.forEach { pkgBuf.put(it) }

        val totalSize = 12 + globalPool.size + pkgSize
        val tableBuf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        tableBuf.putShort(RES_TABLE_TYPE)
        tableBuf.putShort(12)
        tableBuf.putInt(totalSize)
        tableBuf.putInt(1) // packageCount
        tableBuf.put(globalPool)
        tableBuf.put(pkgBuf.array())
        return tableBuf.array()
    }

    private fun encodeTypeSpec(typeId: Int, entryCount: Int): ByteArray {
        val headerSize = 16
        val size = headerSize + entryCount * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_TABLE_TYPE_SPEC_TYPE)
        buf.putShort(headerSize.toShort())
        buf.putInt(size)
        buf.put(typeId.toByte())
        buf.put(0) // res0
        buf.putShort(0) // res1
        buf.putInt(entryCount)
        repeat(entryCount) {
            buf.putInt(0) // default config flags
        }
        return buf.array()
    }

    private data class EncodedEntry(
        val keyIndex: Int,
        val dataType: Byte,
        val data: Int,
    )

    private fun encodeTypeChunk(
        typeId: Int,
        entries: List<EncodedEntry>,
    ): ByteArray {
        val configSize = 64
        val headerSize = 20 + configSize // 84 bytes
        val offsetsSize = entries.size * 4
        val entriesStart = headerSize + offsetsSize
        val eachEntrySize = 16 // 8 bytes ResTable_entry + 8 bytes Res_value
        val totalSize = entriesStart + entries.size * eachEntrySize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_TABLE_TYPE_TYPE)
        buf.putShort(headerSize.toShort())
        buf.putInt(totalSize)
        buf.put(typeId.toByte())
        buf.put(0) // res0
        buf.putShort(0) // res1
        buf.putInt(entries.size)
        buf.putInt(entriesStart)

        // ResTable_config (64 bytes, first 4 bytes = configSize, rest zero for default config)
        buf.putInt(configSize)
        repeat(configSize - 4) { buf.put(0) }

        // Entry offset table
        for (i in entries.indices) {
            buf.putInt(i * eachEntrySize)
        }

        // ResTable_entry + Res_value pairs
        for (entry in entries) {
            buf.putShort(8) // ResTable_entry size
            buf.putShort(0) // flags
            buf.putInt(entry.keyIndex)
            buf.putShort(8) // Res_value size
            buf.put(0) // res0
            buf.put(entry.dataType)
            buf.putInt(entry.data)
        }
        return buf.array()
    }
}
