package com.example.compiler

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-Kotlin Android Binary XML (AXML) encoder for AndroidManifest.xml and simple XML resources.
 * Produces AOSP-compliant ResXMLTree chunks consumable by Android's PackageParser.
 */
object BinaryXmlEncoder {

    private const val RES_STRING_POOL_TYPE: Short = 0x0001
    private const val RES_XML_TYPE: Short = 0x0003
    private const val RES_XML_START_NAMESPACE_TYPE: Short = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE: Short = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE: Short = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE: Short = 0x0103
    private const val RES_XML_RESOURCE_MAP_TYPE: Short = 0x0180

    private const val UTF8_FLAG = 0x00000100

    private const val TYPE_REFERENCE: Byte = 0x01
    private const val TYPE_STRING: Byte = 0x03
    private const val TYPE_INT_DEC: Byte = 0x10
    private const val TYPE_INT_BOOLEAN: Byte = 0x12
    private const val TYPE_INT_COLOR_ARGB8: Byte = 0x1c

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

    // Standard android.R.attr resource IDs used in AndroidManifest.xml
    private val ANDROID_ATTR_IDS: Map<String, Int> = mapOf(
        "theme" to 0x01010000,
        "label" to 0x01010001,
        "icon" to 0x01010002,
        "name" to 0x01010003,
        "permission" to 0x01010006,
        "enabled" to 0x0101000e,
        "exported" to 0x01010010,
        "launchMode" to 0x0101001d,
        "screenOrientation" to 0x0101001e,
        "configChanges" to 0x0101001f,
        "authorities" to 0x01010018,
        "grantUriPermissions" to 0x0101001b,
        "value" to 0x01010024,
        "resource" to 0x01010025,
        "minSdkVersion" to 0x0101020c,
        "versionCode" to 0x0101021b,
        "versionName" to 0x0101021c,
        "windowSoftInputMode" to 0x0101022b,
        "targetSdkVersion" to 0x01010270,
        "allowBackup" to 0x01010280,
        "hardwareAccelerated" to 0x010102d3,
        "required" to 0x0101028e,
        "supportsRtl" to 0x010103af,
        "usesCleartextTraffic" to 0x010104ec,
        "roundIcon" to 0x0101052c,
        "compileSdkVersion" to 0x01010572,
    )

    private val ANDROID_STYLE_IDS: Map<String, Int> = mapOf(
        "Theme.Material.Light" to 0x01030224,
        "Theme.Material.Light.DarkActionBar" to 0x01030239,
        "Theme.Material.Light.NoActionBar" to 0x01030237,
        "Theme.Material" to 0x01030223,
        "Theme.Material.NoActionBar" to 0x01030226,
        "Theme.DeviceDefault" to 0x01030128,
        "Theme.DeviceDefault.Light" to 0x0103012b,
        "Theme.DeviceDefault.Light.DarkActionBar" to 0x0103013f,
        "Theme.DeviceDefault.NoActionBar" to 0x01030129,
        "Theme.Holo.Light" to 0x0103006e,
    )

    private sealed interface XmlEvent {
        data class StartNs(val prefix: String, val uri: String, val line: Int) : XmlEvent
        data class EndNs(val prefix: String, val uri: String, val line: Int) : XmlEvent
        data class StartTag(
            val ns: String?,
            val name: String,
            val attrs: List<Attr>,
            val line: Int,
        ) : XmlEvent

        data class EndTag(val ns: String?, val name: String, val line: Int) : XmlEvent
    }

    private data class Attr(
        val ns: String?,
        val name: String,
        val value: String,
        val resId: Int,
    )

    fun encodeManifest(
        xmlText: String,
        resourceIds: Map<String, Int>,
    ): ByteArray {
        val events = parseXmlEvents(xmlText)

        val attrWithRes = linkedMapOf<String, Int>()
        val otherStrings = linkedSetOf<String>()

        for (event in events) {
            when (event) {
                is XmlEvent.StartNs -> {
                    otherStrings.add(event.prefix)
                    otherStrings.add(event.uri)
                }
                is XmlEvent.EndNs -> {
                    otherStrings.add(event.prefix)
                    otherStrings.add(event.uri)
                }
                is XmlEvent.StartTag -> {
                    event.ns?.takeIf { it.isNotEmpty() }?.let { otherStrings.add(it) }
                    otherStrings.add(event.name)
                    for (attr in event.attrs) {
                        attr.ns?.takeIf { it.isNotEmpty() }?.let { otherStrings.add(it) }
                        if (attr.resId != 0) {
                            attrWithRes.putIfAbsent(attr.name, attr.resId)
                        } else {
                            otherStrings.add(attr.name)
                        }
                        if (resolveTypedValue(attr.value, resourceIds).first == TYPE_STRING) {
                            otherStrings.add(attr.value)
                        }
                    }
                }
                is XmlEvent.EndTag -> {
                    event.ns?.takeIf { it.isNotEmpty() }?.let { otherStrings.add(it) }
                    otherStrings.add(event.name)
                }
            }
        }

        val stringList = mutableListOf<String>()
        val resourceMap = mutableListOf<Int>()
        for ((name, resId) in attrWithRes) {
            stringList.add(name)
            resourceMap.add(resId)
        }
        for (s in otherStrings) {
            if (!attrWithRes.containsKey(s)) {
                stringList.add(s)
            }
        }
        val stringIndexMap = stringList.withIndex().associate { (i, s) -> s to i }

        val stringPoolChunk = encodeUtf8StringPool(stringList)
        val resourceMapChunk = encodeResourceMap(resourceMap)
        val bodyStream = ByteArrayOutputStream()

        for (event in events) {
            when (event) {
                is XmlEvent.StartNs -> {
                    bodyStream.write(
                        encodeNamespaceChunk(
                            type = RES_XML_START_NAMESPACE_TYPE,
                            line = event.line,
                            prefixIdx = stringIndexMap.getValue(event.prefix),
                            uriIdx = stringIndexMap.getValue(event.uri),
                        ),
                    )
                }
                is XmlEvent.EndNs -> {
                    bodyStream.write(
                        encodeNamespaceChunk(
                            type = RES_XML_END_NAMESPACE_TYPE,
                            line = event.line,
                            prefixIdx = stringIndexMap.getValue(event.prefix),
                            uriIdx = stringIndexMap.getValue(event.uri),
                        ),
                    )
                }
                is XmlEvent.StartTag -> {
                    bodyStream.write(
                        encodeStartElementChunk(
                            event = event,
                            stringIndexMap = stringIndexMap,
                            resourceIds = resourceIds,
                        ),
                    )
                }
                is XmlEvent.EndTag -> {
                    bodyStream.write(
                        encodeEndElementChunk(
                            line = event.line,
                            nsIdx = event.ns?.takeIf { it.isNotEmpty() }?.let { stringIndexMap.getValue(it) } ?: -1,
                            nameIdx = stringIndexMap.getValue(event.name),
                        ),
                    )
                }
            }
        }

        val bodyBytes = bodyStream.toByteArray()
        val totalSize = 8 + stringPoolChunk.size + resourceMapChunk.size + bodyBytes.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_XML_TYPE)
        out.putShort(8)
        out.putInt(totalSize)
        out.put(stringPoolChunk)
        out.put(resourceMapChunk)
        out.put(bodyBytes)
        return out.array()
    }

    private fun parseXmlEvents(xmlText: String): List<XmlEvent> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText.trim())))
        val events = mutableListOf<XmlEvent>()
        var lineCounter = 1

        fun visitElement(el: Element) {
            val line = lineCounter++
            val declaredNs = mutableListOf<Pair<String, String>>()
            val attrs = mutableListOf<Attr>()

            val namedMap = el.attributes
            for (i in 0 until namedMap.length) {
                val attrNode = namedMap.item(i)
                val ns = attrNode.namespaceURI?.takeIf { it.isNotEmpty() }
                val nodeName = attrNode.nodeName
                val localName = attrNode.localName ?: nodeName.substringAfter(':')
                val value = attrNode.nodeValue ?: ""

                if (ns == XMLNS_NS || nodeName.startsWith("xmlns")) {
                    val prefix = if (nodeName.contains(':')) nodeName.substringAfter(':') else ""
                    declaredNs.add(prefix to value)
                    events.add(XmlEvent.StartNs(prefix, value, line))
                } else {
                    val resId = if (ns == ANDROID_NS) ANDROID_ATTR_IDS[localName] ?: 0 else 0
                    attrs.add(Attr(ns, localName, value, resId))
                }
            }

            val sortedAttrs = attrs.sortedWith(
                compareBy<Attr>(
                    { if (it.resId == 0) 1 else 0 },
                    { it.resId },
                    { it.name },
                ),
            )
            events.add(
                XmlEvent.StartTag(
                    ns = el.namespaceURI?.takeIf { it.isNotEmpty() },
                    name = el.localName ?: el.tagName,
                    attrs = sortedAttrs,
                    line = line,
                ),
            )

            val children = el.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    visitElement(child as Element)
                }
            }

            events.add(
                XmlEvent.EndTag(
                    ns = el.namespaceURI?.takeIf { it.isNotEmpty() },
                    name = el.localName ?: el.tagName,
                    line = lineCounter,
                ),
            )
            for ((prefix, uri) in declaredNs.asReversed()) {
                events.add(XmlEvent.EndNs(prefix, uri, lineCounter))
            }
        }

        visitElement(doc.documentElement)
        return events
    }

    private fun resolveTypedValue(
        raw: String,
        resourceIds: Map<String, Int>,
    ): Pair<Byte, Int> {
        if (raw == "true") return TYPE_INT_BOOLEAN to -1
        if (raw == "false") return TYPE_INT_BOOLEAN to 0

        if (raw.startsWith("@android:style/")) {
            val styleName = raw.removePrefix("@android:style/")
            ANDROID_STYLE_IDS[styleName]?.let { return TYPE_REFERENCE to it }
        }
        if (raw.startsWith("@string/") || raw.startsWith("@color/")) {
            val key = raw.removePrefix("@")
            resourceIds[key]?.let { return TYPE_REFERENCE to it }
        }
        if (raw.startsWith("#")) {
            val hex = raw.removePrefix("#")
            val parsed = when (hex.length) {
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> null
            }
            if (parsed != null) return TYPE_INT_COLOR_ARGB8 to parsed
        }
        raw.toIntOrNull()?.let { return TYPE_INT_DEC to it }
        return TYPE_STRING to 0
    }

    internal fun encodeUtf8StringPool(strings: List<String>): ByteArray {
        val offsets = IntArray(strings.size)
        val data = ByteArrayOutputStream()
        for ((index, str) in strings.withIndex()) {
            offsets[index] = data.size()
            val utf8 = str.toByteArray(Charsets.UTF_8)
            writeUtf8Length(data, str.length)
            writeUtf8Length(data, utf8.size)
            data.write(utf8)
            data.write(0)
        }
        while (data.size() % 4 != 0) {
            data.write(0)
        }
        val strBytes = data.toByteArray()
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val totalSize = stringsStart + strBytes.size

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE)
        buf.putShort(headerSize.toShort())
        buf.putInt(totalSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(UTF8_FLAG)
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart
        for (offset in offsets) {
            buf.putInt(offset)
        }
        buf.put(strBytes)
        return buf.array()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, len: Int) {
        if (len >= 0x80) {
            out.write(((len shr 8) and 0x7F) or 0x80)
        }
        out.write(len and 0xFF)
    }

    private fun encodeResourceMap(resIds: List<Int>): ByteArray {
        val size = 8 + resIds.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_RESOURCE_MAP_TYPE)
        buf.putShort(8)
        buf.putInt(size)
        for (id in resIds) {
            buf.putInt(id)
        }
        return buf.array()
    }

    private fun encodeNamespaceChunk(
        type: Short,
        line: Int,
        prefixIdx: Int,
        uriIdx: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(type)
        buf.putShort(16)
        buf.putInt(24)
        buf.putInt(line)
        buf.putInt(-1) // comment
        buf.putInt(prefixIdx)
        buf.putInt(uriIdx)
        return buf.array()
    }

    private fun encodeStartElementChunk(
        event: XmlEvent.StartTag,
        stringIndexMap: Map<String, Int>,
        resourceIds: Map<String, Int>,
    ): ByteArray {
        val size = 36 + event.attrs.size * 20
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_START_ELEMENT_TYPE)
        buf.putShort(16) // ResXMLTree_node headerSize
        buf.putInt(size)
        buf.putInt(event.line)
        buf.putInt(-1) // comment

        buf.putInt(event.ns?.let { stringIndexMap.getValue(it) } ?: -1)
        buf.putInt(stringIndexMap.getValue(event.name))
        buf.putShort(20) // attributeStart
        buf.putShort(20) // attributeSize
        buf.putShort(event.attrs.size.toShort())
        buf.putShort(0) // idIndex
        buf.putShort(0) // classIndex
        buf.putShort(0) // styleIndex

        for (attr in event.attrs) {
            val (dataType, resolvedData) = resolveTypedValue(attr.value, resourceIds)
            val rawValueIdx = if (dataType == TYPE_STRING) {
                stringIndexMap.getValue(attr.value)
            } else {
                -1
            }
            val dataValue = if (dataType == TYPE_STRING) rawValueIdx else resolvedData

            buf.putInt(attr.ns?.let { stringIndexMap.getValue(it) } ?: -1)
            buf.putInt(stringIndexMap.getValue(attr.name))
            buf.putInt(rawValueIdx)
            buf.putShort(8) // Res_value size
            buf.put(0) // res0
            buf.put(dataType)
            buf.putInt(dataValue)
        }
        return buf.array()
    }

    private fun encodeEndElementChunk(line: Int, nsIdx: Int, nameIdx: Int): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_END_ELEMENT_TYPE)
        buf.putShort(16)
        buf.putInt(24)
        buf.putInt(line)
        buf.putInt(-1)
        buf.putInt(nsIdx)
        buf.putInt(nameIdx)
        return buf.array()
    }
}
