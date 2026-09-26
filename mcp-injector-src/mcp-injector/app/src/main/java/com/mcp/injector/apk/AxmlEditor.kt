package com.mcp.injector.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.ArrayDeque

/**
 * 二进制 AndroidManifest.xml 编辑器（任务 C 支撑文件）。
 *
 * 对齐反编译产物 AxmlEditor：在保留原 chunk 结构的前提下重建 string pool /
 * resource map，并插入新的元素 chunk：
 * - [ensureInternetPermission]：补 `uses-permission android.permission.INTERNET`
 * - [injectService]：注入 BridgeService（action=[BRIDGE_ACTION]，exported=true）
 * - [injectReceiver]：注入 AgentReceiver（action=[AGENT_ACTION]，exported=true）
 */
object AxmlEditor {

    const val BRIDGE_SERVICE = "com.mcp.injector.bridge.BridgeService"
    const val BRIDGE_ACTION = "com.mcp.injector.bridge.BRIDGE"
    const val AGENT_RECEIVER = "com.mcp.injector.bridge.AgentReceiver"
    const val AGENT_ACTION = "com.mcp.injector.agent.CONTROL"
    const val BRIDGE_INIT_PROVIDER = "com.mcp.injector.bridge.BridgeInitProvider"
    const val INTERNET_PERMISSION = "android.permission.INTERNET"

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private const val CHUNK_XML = 3
    private const val CHUNK_STRING_POOL = 1
    private const val CHUNK_RESOURCE_MAP = 384
    private const val CHUNK_START_NS = 256
    private const val CHUNK_END_NS = 257
    private const val CHUNK_START_ELEM = 258
    private const val CHUNK_END_ELEM = 259

    /** android:name = 0x01010003 */
    private const val ATTR_NAME_ID = 16842755
    /** android:exported = 0x01010010 */
    private const val ATTR_EXPORTED_ID = 16842768
    /** android:authorities = 0x01010018 */
    private const val ATTR_AUTHORITIES_ID = 16842776

    private const val TYPE_STRING = 3
    private const val TYPE_INT_BOOLEAN = 18
    private const val NO_INDEX = -1

    // ---------------------------------------------------------------------
    // 公开编辑入口
    // ---------------------------------------------------------------------

    /** 若清单没有 INTERNET 权限则插入（返回新字节；已存在则原样返回）。 */
    fun ensureInternetPermission(original: ByteArray): ByteArray {
        val ctx = EditContext(original)
        if (ctx.originalStrings.contains(INTERNET_PERMISSION)) return original
        val app = ctx.findApplication() ?: throw IllegalStateException("清单缺少 application 节点")
        val nsUriIdx = ctx.indexOf(ANDROID_NS)
        val nsPrefixIdx = ctx.indexOf("android")
        val needNs = !ctx.declaresAndroidNs()
        val permIdx = ctx.indexOf("uses-permission")
        val nameAttrIdx = ctx.indexOfAttrName("name", ATTR_NAME_ID)
        val permStrIdx = ctx.indexOf(INTERNET_PERMISSION)
        val insertion = ctx.buildElements(nsUriIdx) {
            if (needNs) startNamespace(nsPrefixIdx, nsUriIdx)
            startElement(permIdx) {
                attribute(nameAttrIdx, permStrIdx, TYPE_STRING, 0)
            }
            endElement(permIdx)
            if (needNs) endNamespace(nsPrefixIdx, nsUriIdx)
        }
        return ctx.assemble(mapOf(app.first to insertion))
    }

    /** 在 application 末尾注入 BridgeService（含 intent-filter）。 */
    fun injectService(original: ByteArray): ByteArray {
        val ctx = EditContext(original)
        // 幂等：避免重复注入时插入重复的 service 声明。
        if (ctx.originalStrings.contains(BRIDGE_SERVICE)) return original
        val app = ctx.findApplication() ?: throw IllegalStateException("清单缺少 application 节点")
        val nsUriIdx = ctx.indexOf(ANDROID_NS)
        val nsPrefixIdx = ctx.indexOf("android")
        val needNs = !ctx.declaresAndroidNs()
        val serviceIdx = ctx.indexOf("service")
        val filterIdx = ctx.indexOf("intent-filter")
        val actionIdx = ctx.indexOf("action")
        val nameAttrIdx = ctx.indexOfAttrName("name", ATTR_NAME_ID)
        val exportedAttrIdx = ctx.indexOfAttrName("exported", ATTR_EXPORTED_ID)
        val svcNameIdx = ctx.indexOf(BRIDGE_SERVICE)
        val svcActionIdx = ctx.indexOf(BRIDGE_ACTION)
        val insertion = ctx.buildElements(nsUriIdx) {
            if (needNs) startNamespace(nsPrefixIdx, nsUriIdx)
            startElement(serviceIdx) {
                attribute(nameAttrIdx, svcNameIdx, TYPE_STRING, 0)
                attribute(exportedAttrIdx, NO_INDEX, TYPE_INT_BOOLEAN, 1)
            }
            startElement(filterIdx)
            startElement(actionIdx) {
                attribute(nameAttrIdx, svcActionIdx, TYPE_STRING, 0)
            }
            endElement(actionIdx)
            endElement(filterIdx)
            endElement(serviceIdx)
            if (needNs) endNamespace(nsPrefixIdx, nsUriIdx)
        }
        return ctx.assemble(mapOf(app.second to insertion))
    }

    /** 在 application 末尾注入 AgentReceiver（exported=true，action=[AGENT_ACTION]）。 */
    fun injectReceiver(original: ByteArray): ByteArray {
        val ctx = EditContext(original)
        // 幂等：对已注入产物重复注入时，避免在清单里插入重复的 receiver 声明。
        if (ctx.originalStrings.contains(AGENT_RECEIVER)) return original
        val app = ctx.findApplication() ?: throw IllegalStateException("清单缺少 application 节点")
        val nsUriIdx = ctx.indexOf(ANDROID_NS)
        val nsPrefixIdx = ctx.indexOf("android")
        val needNs = !ctx.declaresAndroidNs()
        val receiverIdx = ctx.indexOf("receiver")
        val filterIdx = ctx.indexOf("intent-filter")
        val actionIdx = ctx.indexOf("action")
        val nameAttrIdx = ctx.indexOfAttrName("name", ATTR_NAME_ID)
        val exportedAttrIdx = ctx.indexOfAttrName("exported", ATTR_EXPORTED_ID)
        val rcvrNameIdx = ctx.indexOf(AGENT_RECEIVER)
        val rcvrActionIdx = ctx.indexOf(AGENT_ACTION)
        val insertion = ctx.buildElements(nsUriIdx) {
            if (needNs) startNamespace(nsPrefixIdx, nsUriIdx)
            startElement(receiverIdx) {
                attribute(nameAttrIdx, rcvrNameIdx, TYPE_STRING, 0)
                attribute(exportedAttrIdx, NO_INDEX, TYPE_INT_BOOLEAN, 1)
            }
            startElement(filterIdx)
            startElement(actionIdx) {
                attribute(nameAttrIdx, rcvrActionIdx, TYPE_STRING, 0)
            }
            endElement(actionIdx)
            endElement(filterIdx)
            endElement(receiverIdx)
            if (needNs) endNamespace(nsPrefixIdx, nsUriIdx)
        }
        return ctx.assemble(mapOf(app.second to insertion))
    }

    /**
     * 在 application 末尾注入 BridgeInitProvider（注入引导入口，provider 策略）。
     *
     * 这是目标 App 启动时**最早**能执行注入代码的位置：ContentProvider.onCreate()
     * 在 Application.attachBaseContext() 之后、Application.onCreate() 之前回调。
     *
     * 最小权限：exported=false（无需对外提供数据）、无 intent-filter；authority 用
     * `<目标包名>.mcp-injector.bridge.init` 保证设备内唯一，避免 INSTALL_FAILED_CONFLICTING_PROVIDER。
     */
    fun injectProvider(original: ByteArray, authority: String): ByteArray {
        val ctx = EditContext(original)
        // 幂等：重复注入时避免插入重复的 provider 声明。
        if (ctx.originalStrings.contains(BRIDGE_INIT_PROVIDER)) return original
        val app = ctx.findApplication() ?: throw IllegalStateException("清单缺少 application 节点")
        val nsUriIdx = ctx.indexOf(ANDROID_NS)
        val nsPrefixIdx = ctx.indexOf("android")
        val needNs = !ctx.declaresAndroidNs()
        val providerIdx = ctx.indexOf("provider")
        val nameAttrIdx = ctx.indexOfAttrName("name", ATTR_NAME_ID)
        val authAttrIdx = ctx.indexOfAttrName("authorities", ATTR_AUTHORITIES_ID)
        val exportedAttrIdx = ctx.indexOfAttrName("exported", ATTR_EXPORTED_ID)
        val clsIdx = ctx.indexOf(BRIDGE_INIT_PROVIDER)
        val authorityIdx = ctx.indexOf(authority)
        val insertion = ctx.buildElements(nsUriIdx) {
            if (needNs) startNamespace(nsPrefixIdx, nsUriIdx)
            startElement(providerIdx) {
                attribute(nameAttrIdx, clsIdx, TYPE_STRING, 0)
                attribute(authAttrIdx, authorityIdx, TYPE_STRING, 0)
                attribute(exportedAttrIdx, NO_INDEX, TYPE_INT_BOOLEAN, 0)
            }
            endElement(providerIdx)
            if (needNs) endNamespace(nsPrefixIdx, nsUriIdx)
        }
        return ctx.assemble(mapOf(app.second to insertion))
    }

    private class Chunk(val type: Int, val offset: Int, val size: Int)

    /** 编辑上下文：持有原始 chunk 列表、字符串池与 application 位置。 */
    private class EditContext(original: ByteArray) {
        val original: ByteArray = original
        val buf: ByteBuffer = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val chunks: List<Chunk>
        val originalStrings: List<String>
        val originalCount: Int
        private val strings = mutableListOf<String>()
        private val attrNameIds = LinkedHashMap<Int, Int>()

        init {
            if (buf.short != CHUNK_XML.toShort()) throw IllegalArgumentException("不是有效的清单文件")
            // XML chunk 头是 type(u16) + headerSize(u16) + size(u32)，
            // 读 size 前必须跳过 headerSize，否则会把 headerSize 当作 size 的高 16 位，
            // 得到错位长度 → buf.position(越界) → 注入必然失败。
            buf.short // headerSize
            val fileSize = buf.int
            val limit = buf.limit()
            if (fileSize !in 8..limit) throw IllegalArgumentException("清单长度非法: $fileSize/$limit")
            val list = mutableListOf<Chunk>()
            buf.position(8)
            while (buf.position() + 8 <= fileSize) {
                val pos = buf.position()
                val type = buf.short.toInt()
                buf.short // 同样必须跳过每个 chunk 头的 headerSize
                val size = buf.int
                // 防御：非法长度立即给出可读错误，避免后续 ByteBuffer.position 抛出难以定位的底层异常。
                if (size < 8 || pos + size > limit) {
                    throw IllegalArgumentException("清单 chunk 越界: type=$type pos=$pos size=$size limit=$limit")
                }
                list.add(Chunk(type, pos, size))
                buf.position(pos + size)
            }
            chunks = list
            val pool = chunks.firstOrNull { it.type == CHUNK_STRING_POOL }
                ?: throw IllegalStateException("清单缺少字符串池")
            originalStrings = readStrings(pool.offset)
            originalCount = originalStrings.size
            strings.addAll(originalStrings)
        }

        fun indexOf(s: String): Int {
            val i = strings.indexOf(s)
            if (i >= 0) return i
            strings.add(s)
            return strings.size - 1
        }

        fun indexOfAttrName(s: String, resId: Int): Int {
            val i = indexOf(s)
            if (i >= originalCount) attrNameIds[i] = resId
            return i
        }

        fun declaresAndroidNs(): Boolean {
            for (c in chunks) {
                if (c.type == CHUNK_START_NS) {
                    val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    b.position(c.offset + 20)
                    val uriIdx = b.int
                    if (uriIdx in 0 until originalStrings.size && originalStrings[uriIdx] == ANDROID_NS) return true
                }
            }
            return false
        }

        /** 定位 application 元素：返回 Pair(开始 chunk 偏移, 结束 chunk 偏移)。 */
        fun findApplication(): Pair<Int, Int>? {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            b.position(8)
            val stack = ArrayDeque<Int>()
            var start: Int? = null
            while (b.remaining() >= 8) {
                val pos = b.position()
                val type = b.short.toInt()
                b.short
                val size = b.int
                // 非法长度必须终止扫描：否则 size < 8 时 b.position(pos + size) 不前进（甚至后退），
                // 会陷入死循环。与 EditContext.init 的越界守卫保持一致。
                if (size < 8 || pos + size > b.limit()) return null
                if (type == CHUNK_START_ELEM) {
                    b.int; b.int; b.int
                    val nameIdx = b.int
                    if (start == null && nameIdx < originalStrings.size && originalStrings[nameIdx] == "application") {
                        start = pos
                    }
                    stack.addLast(nameIdx)
                } else if (type == CHUNK_END_ELEM) {
                    // 不能用 `?: continue`：continue 会跳过下面的 b.position(pos + size)，
                    // 让缓冲区停在 chunk 中间，后续把数据当 chunk 头解析而错位。
                    val nameIdx = stack.removeLastOrNull()
                    if (nameIdx != null &&
                        nameIdx < originalStrings.size &&
                        originalStrings[nameIdx] == "application"
                    ) {
                        return Pair(start ?: pos, pos)
                    }
                }
                b.position(pos + size)
            }
            return null
        }

        fun buildElements(nsAttrIdx: Int, block: ElementWriter.() -> Unit): ByteArray {
            val w = ElementWriter(nsAttrIdx)
            w.block()
            return w.out.toByteArray()
        }

        /** 按原始 chunk 重排输出，在 insertions[offset] 处插入新 chunk 字节。 */
        fun assemble(insertions: Map<Int, ByteArray>): ByteArray {
            val poolChunk = chunks.firstOrNull { it.type == CHUNK_STRING_POOL }
            val mapChunk = chunks.firstOrNull { it.type == CHUNK_RESOURCE_MAP }
            val newPool = buildStringPool(strings)
            var mapBytes: ByteArray? = null
            if (mapChunk != null) {
                val ids = readResourceMapIds(mapChunk.offset).toMutableList()
                while (ids.size < strings.size) ids.add(0)
                for ((idx, resId) in attrNameIds) {
                    if (idx >= ids.size || ids[idx] == 0) ids[idx] = resId
                }
                mapBytes = buildResourceMap(ids)
            }
            var total = newPool.size + 8
            if (mapBytes != null) total += mapBytes.size
            for (c in chunks) {
                if (c.type == CHUNK_STRING_POOL || c.type == CHUNK_RESOURCE_MAP) continue
                total += c.size
                insertions[c.offset]?.let { total += it.size }
            }
            val out = ByteArrayOutputStream()
            writeShort(out, CHUNK_XML)
            writeShort(out, 8)
            writeInt(out, total)
            out.write(newPool)
            mapBytes?.let { out.write(it) }
            for (c in chunks) {
                if (c.type == CHUNK_STRING_POOL || c.type == CHUNK_RESOURCE_MAP) continue
                insertions[c.offset]?.let { out.write(it) }
                out.write(original, c.offset, c.size)
            }
            return out.toByteArray()
        }

        private fun readStrings(offset: Int): List<String> {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            b.position(offset + 8)
            val count = b.int
            val styleCount = b.int
            val flags = b.int
            val stringsStart = b.int
            b.int // stylesStart
            // styleCount > 0 时字符串数据本身仍可正常读取（样式表位于字符串数据之后），
            // 这里不再直接失败；重建字符串池会丢弃样式信息，而 AndroidManifest 极少包含样式。
            val utf8 = (flags and 0x100) != 0
            val offsets = IntArray(count) { b.int }
            return List(count) { i ->
                try {
                    b.position(offset + stringsStart + offsets[i])
                    val s = if (utf8) {
                        if ((b.get().toInt() and 0x80) != 0) b.get()
                        var len = b.get().toInt() and 0xFF
                        if ((len and 0x80) != 0) len = (b.get().toInt() and 0xFF) or ((len and 0x7F) shl 8)
                        val arr = ByteArray(len)
                        b.get(arr)
                        String(arr, Charsets.UTF_8)
                    } else {
                        var len = b.short.toInt() and 0xFFFF
                        if ((len and 0x8000) != 0) len = ((len and 0x7FFF) shl 16) or (b.short.toInt() and 0xFFFF)
                        val sb = StringBuilder(len)
                        repeat(len) { sb.append(b.short.toInt().toChar()) }
                        sb.toString()
                    }
                    s
                } catch (t: Throwable) {
                    "?"
                }
            }
        }

        private fun readResourceMapIds(offset: Int): IntArray {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            b.position(offset)
            b.short; b.short
            val count = (b.int - 8) / 4
            return IntArray(count) { b.int }
        }
    }

    /** 元素写入器：向 ByteArrayOutputStream 写元素 chunk 字节。 */
    private class ElementWriter(private val nsIdx: Int) {
        val out = ByteArrayOutputStream()
        private var attrSink: ByteArrayOutputStream? = null

        fun startNamespace(prefixIdx: Int, uriIdx: Int) {
            writeNamespaceChunk(out, CHUNK_START_NS, prefixIdx, uriIdx)
        }

        fun endNamespace(prefixIdx: Int, uriIdx: Int) {
            writeNamespaceChunk(out, CHUNK_END_NS, prefixIdx, uriIdx)
        }

        fun startElement(nameIdx: Int, attrs: ElementWriter.() -> Unit = {}) {
            check(attrSink == null) { "属性不能嵌套" }
            val sink = ByteArrayOutputStream()
            attrSink = sink
            attrs()
            attrSink = null
            writeShort(out, CHUNK_START_ELEM)
            writeShort(out, 16)
            writeInt(out, sink.size() + 36)
            writeInt(out, 1) // lineNumber
            writeInt(out, -1) // comment
            writeInt(out, -1) // ns
            writeInt(out, nameIdx)
            writeShort(out, 20) // attrStart
            writeShort(out, 20) // attrSize
            writeShort(out, sink.size() / 20) // attrCount
            writeShort(out, 0) // idIndex
            writeShort(out, 0) // classIndex
            writeShort(out, 0) // styleIndex
            out.write(sink.toByteArray())
        }

        fun endElement(nameIdx: Int) {
            writeShort(out, CHUNK_END_ELEM)
            writeShort(out, 16)
            writeInt(out, 24)
            writeInt(out, 1)
            writeInt(out, -1)
            writeInt(out, -1)
            writeInt(out, nameIdx)
        }

        /** 写入一条 20 字节属性。valueType 非 TYPE_STRING 时 valueData 作为数值。 */
        fun attribute(nameIdx: Int, rawValueIdx: Int, valueType: Int, valueData: Int) {
            val sink = attrSink ?: throw IllegalStateException("attribute 必须写在 startElement 代码块内")
            writeInt(sink, nsIdx)
            writeInt(sink, nameIdx)
            writeInt(sink, rawValueIdx)
            writeShort(sink, 8)
            sink.write(0)
            sink.write(valueType)
            writeInt(sink, if (valueType != TYPE_STRING) valueData else rawValueIdx)
        }
    }

    private fun writeNamespaceChunk(out: ByteArrayOutputStream, type: Int, prefixIdx: Int, uriIdx: Int) {
        writeShort(out, type)
        writeShort(out, 16)
        writeInt(out, 24)
        writeInt(out, 1)
        writeInt(out, -1)
        writeInt(out, prefixIdx)
        writeInt(out, uriIdx)
    }

    private fun buildStringPool(strings: List<String>): ByteArray {
        val encoded = strings.map { s ->
            val b = ByteArrayOutputStream()
            val bytes = s.toByteArray(Charsets.UTF_16LE)
            val len = s.length
            if (len >= 32768) {
                writeShort(b, 32768 or (len shr 16))
                writeShort(b, len and 0xFFFF)
            } else {
                writeShort(b, len)
            }
            b.write(bytes)
            writeShort(b, 0)
            b.toByteArray()
        }
        val headerSize = strings.size * 4 + 28
        val pad = (4 - (headerSize % 4)) % 4
        var dataStart = headerSize + pad
        val offsets = IntArray(strings.size)
        for (i in encoded.indices) {
            offsets[i] = dataStart - (headerSize + pad) // 相对 stringsStart(=headerSize+pad)
            dataStart += encoded[i].size
        }
        val dataSize = dataStart - (headerSize + pad)
        val total = ((headerSize + pad + dataSize + 3) / 4) * 4
        val tailPad = total - headerSize - pad - dataSize
        val out = ByteArrayOutputStream()
        writeShort(out, CHUNK_STRING_POOL)
        writeShort(out, 28)
        writeInt(out, total)
        writeInt(out, strings.size)
        writeInt(out, 0)
        writeInt(out, 0)
        writeInt(out, headerSize + pad)
        writeInt(out, 0)
        offsets.forEach { writeInt(out, it) }
        repeat(pad) { out.write(0) }
        encoded.forEach { out.write(it) }
        repeat(tailPad) { out.write(0) }
        check(out.size() == total) { "字符串池长度与 chunk 头不一致" }
        return out.toByteArray()
    }

    private fun buildResourceMap(ids: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        writeShort(out, CHUNK_RESOURCE_MAP)
        writeShort(out, 8)
        writeInt(out, ids.size * 4 + 8)
        ids.forEach { writeInt(out, it) }
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
