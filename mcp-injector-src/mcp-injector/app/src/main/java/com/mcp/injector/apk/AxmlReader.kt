package com.mcp.injector.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.ArrayDeque

/**
 * 二进制 AndroidManifest.xml 只读解析器（任务 C 支撑文件）。
 *
 * 对齐反编译产物 AxmlReader：解析 string pool 后按 chunk 遍历，
 * 产出 [Element] 树（name + attrs + children）。
 */
object AxmlReader {

    private const val CHUNK_STRING_POOL = 1
    private const val CHUNK_START_ELEM = 258
    private const val CHUNK_END_ELEM = 259
    private const val CHUNK_CDATA = 260

    private const val TYPE_STRING = 3
    private const val TYPE_REFERENCE = 1
    private const val TYPE_INT_BOOLEAN = 18
    private const val TYPE_INT_DEC = 16
    private const val TYPE_INT_HEX = 17

    /** 清单元素：名称 + 属性（原始 Value）+ 子元素。 */
    class Element(
        val name: String,
        val attrs: Map<String, Value>,
    ) {
        val children: MutableList<Element> = mutableListOf()

        fun child(tag: String): Element? = children.firstOrNull { it.name == tag }

        fun children(tag: String): List<Element> = children.filter { it.name == tag }
    }

    /** 属性值：type / data / 字符串。 */
    class Value(val type: Int, val data: Int, val str: String?) {
        fun asString(): String? = str

        fun asBoolean(): Boolean? = when {
            type == TYPE_INT_BOOLEAN -> data != 0
            str != null -> str.toBooleanStrictOrNull()
            else -> null
        }

        fun asInt(): Int? = when (type) {
            TYPE_REFERENCE -> null
            TYPE_INT_DEC, TYPE_INT_HEX -> data
            else -> str?.toIntOrNull()
        }
    }

    /** 解析二进制 XML，返回根元素。 */
    fun parse(bytes: ByteArray): Element {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.short != 3.toShort()) throw IllegalArgumentException("不是有效的二进制 XML 文件")
        try {
            return buildTree(buf)
        } catch (t: IndexOutOfBoundsException) {
            throw IllegalStateException("解析 AndroidManifest.xml 失败: ${t.message}", t)
        } catch (t: IllegalArgumentException) {
            throw IllegalStateException("解析 AndroidManifest.xml 失败: ${t.message}", t)
        }
    }

    private fun buildTree(buf: ByteBuffer): Element {
        // 1. 定位 string pool
        var strings = emptyArray<String>()
        // ByteBuffer.duplicate() 不继承字节序，返回的永远是 BIG_ENDIAN；若在此漏掉
        // order(LITTLE_ENDIAN)，会把 type=1 读成 0x0100、把 chunk size 读成十几亿的
        // 假长度，随后 scan.position(pos+size) 越界抛 "Bad position …/limit"，
        // 清单解析直接失败（表现为「注入失败」）。必须显式设置。
        val scan = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        scan.position(8)
        while (scan.remaining() >= 8) {
            val pos = scan.position()
            val type = scan.short.toInt()
            scan.short // header size
            val size = scan.int
            if (type == CHUNK_STRING_POOL) {
                scan.position(pos)
                strings = readStringPool(scan.slice().order(ByteOrder.LITTLE_ENDIAN))
                break
            }
            // 防御：坏长度立即停止扫描（而不是让 position 抛出难定位的底层异常）。
            if (size < 8 || pos + size > scan.limit()) break
            scan.position(pos + size)
        }

        // 2. 遍历 chunk 建树
        val stack = ArrayDeque<Element>()
        var root: Element? = null
        buf.position(8)
        while (buf.remaining() >= 8) {
            val pos = buf.position()
            val type = buf.short.toInt()
            buf.short // header size
            val size = buf.int
            if (size < 8 || pos + size > buf.limit()) break
            if (type == CHUNK_START_ELEM) {
                buf.int // lineNumber
                buf.int // comment
                buf.int // ns
                val nameIdx = buf.int
                buf.short // attrStart
                buf.short // attrSize
                val attrCount = buf.short.toInt()
                buf.short // idIndex / classIndex / styleIndex
                val name = at(strings, nameIdx) ?: "?"
                val attrs = LinkedHashMap<String, Value>()
                val attrBase = pos + size - attrCount * 20
                repeat(attrCount) { i ->
                    buf.position(attrBase + i * 20)
                    buf.int // ns
                    val rawNameIdx = buf.int
                    val rawValueIdx = buf.int
                    buf.short // rawValueSize
                    buf.get() // rawValueType
                    val valueType = buf.get().toInt() and 0xFF
                    val data = buf.int
                    val rawName = at(strings, rawNameIdx)
                    if (rawName != null) {
                        var str = at(strings, rawValueIdx)
                        if (valueType == TYPE_STRING && str == null) str = rawName
                        attrs[rawName] = Value(valueType, data, str)
                    }
                }
                val el = Element(name, attrs)
                stack.lastOrNull()?.children?.add(el)
                if (stack.isEmpty() && root == null) root = el
                stack.addLast(el)
            } else if (type == CHUNK_END_ELEM && stack.isNotEmpty()) {
                stack.removeLast()
            }
            buf.position(pos + size)
        }
        return root ?: throw IllegalStateException("清单缺少根元素")
    }

    private fun readStringPool(pool: ByteBuffer): Array<String> {
        pool.position(8)
        val stringCount = pool.int
        val styleCount = pool.int
        val flags = pool.int
        val stringsStart = pool.int
        pool.int // stylesStart
        val utf8 = (flags and 0x100) != 0
        val offsets = IntArray(stringCount) { pool.int }
        // 样式表位于字符串数据之后，不影响按 offsets 读取字符串本身；这里不再因
        // styleCount > 0 直接放弃（否则带样式的清单会被解析成空字符串池，导致
        // package 等属性全部缺失、解析报错）。与 AxmlEditor.readStrings 行为对齐。
        return Array(stringCount) { i ->
            val strPos = offsets[i] + stringsStart
            if (strPos < 0 || strPos >= pool.limit()) {
                "?@$i"
            } else {
                try {
                    pool.position(strPos)
                    val s = if (utf8) {
                        if ((pool.get().toInt() and 0x80) != 0) pool.get()
                        var len = pool.get().toInt() and 0xFF
                        if ((len and 0x80) != 0) len = (pool.get().toInt() and 0xFF) or ((len and 0x7F) shl 8)
                        val b = ByteArray(len)
                        pool.get(b)
                        String(b, Charsets.UTF_8)
                    } else {
                        var len = pool.short.toInt() and 0xFFFF
                        if ((len and 0x8000) != 0) len = ((len and 0x7FFF) shl 16) or (pool.short.toInt() and 0xFFFF)
                        val sb = StringBuilder(len)
                        repeat(len) { sb.append(pool.short.toInt().toChar()) }
                        sb.toString()
                    }
                    s
                } catch (t: Throwable) {
                    "?@$i"
                }
            }
        }
    }

    private fun at(strings: Array<String>, idx: Int): String? =
        if (idx < 0 || idx >= strings.size) null else strings[idx]
}
