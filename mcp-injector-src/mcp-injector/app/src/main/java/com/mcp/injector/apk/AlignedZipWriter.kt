package com.mcp.injector.apk

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 带对齐的 zip 写出器（任务 C 支撑文件）。
 *
 * 对齐反编译产物 AlignedZipWriter：仅支持 STORED / DEFLATED 两种方法，
 * STORED 条目支持 4 字节对齐（resources.arsc 等），并写入 zip 标准头/中央目录/EOCD。
 */
class AlignedZipWriter(private val output: OutputStream) {

    private class Entry(
        val name: String,
        val method: Int,
        val data: ByteArray,
        val rawSize: Int,
        val crc: Long,
        val align: Int,
    )

    private val entries = mutableListOf<Entry>()
    private var closed = false

    fun add(name: String, data: ByteArray, method: Int, rawSize: Int, crc: Long, align: Int) {
        check(!closed) { "zip 已关闭" }
        entries.add(Entry(name, method, data, rawSize, crc, effectiveAlign(name, method, align)))
    }

    /**
     * 计算条目的有效对齐值。
     *
     * 未压缩的原生库（STORED 的 `lib/**/*.so`）必须按内存页对齐：当目标 APK
     * 声明 `android:extractNativeLibs="false"` 时，系统会直接从 APK 中的 .so
     * 加载，若未按页对齐，安装后 dlopen 会报 `... not page aligned`。
     * 因此这里把有效对齐提升到页大小（默认 [PAGE_ALIGNMENT] = 4096；若调用方
     * 传入更大的值，例如面向 16KB 页设备的 16384，则取传入值）。
     * `resources.arsc` 等其他 STORED 条目仍保持调用方传入的 4 字节对齐。
     */
    private fun effectiveAlign(name: String, method: Int, align: Int): Int {
        if (method == METHOD_STORED && name.startsWith("lib/") && name.endsWith(".so")) {
            return maxOf(align, PAGE_ALIGNMENT)
        }
        return align
    }

    fun addStored(name: String, data: ByteArray, align: Int = 4) {
        add(name, data, METHOD_STORED, data.size, crcOf(data), align)
    }

    fun addDeflated(name: String, data: ByteArray) {
        add(name, deflate(data), METHOD_DEFLATED, data.size, crcOf(data), 0)
    }

    private fun crcOf(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 16)
        val buf = ByteArray(65536)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }

    fun close() {
        check(!closed) { "zip 已关闭" }
        closed = true
        val names = entries.map { it.name.toByteArray(Charsets.UTF_8) }
        val central = ByteArrayOutputStream()
        var offset = 0
        for ((i, e) in entries.withIndex()) {
            val name = names[i]
            var extraLen = 0
            if (e.method == METHOD_STORED && e.align > 1) {
                // 数据起始偏移 = base + extraLen，需满足 (base + extraLen) % align == 0。
                // extraLen 至少 4 字节以容纳 padding extra 头（id 2 + len 2）。
                val base = offset + 30 + name.size
                val pad = (e.align - ((base + 4) % e.align)) % e.align
                extraLen = 4 + pad
            }
            writeLocalHeader(e, name, extraLen)
            if (extraLen > 0) {
                val padLen = extraLen - 4
                output.write(0xDD.toByte().toInt())
                output.write(0x09)
                output.write(padLen and 0xFF)
                output.write((padLen shr 8) and 0xFF)
                repeat(padLen) { output.write(0) }
            }
            output.write(e.data)
            writeCentralRecord(central, e, name, offset)
            offset += 30 + name.size + extraLen + e.data.size
        }
        val centralBytes = central.toByteArray()
        output.write(centralBytes)
        writeInt(EOCD_SIG)
        writeShort(0)
        writeShort(0)
        writeShort(entries.size)
        writeShort(entries.size)
        writeInt(centralBytes.size)
        writeInt(offset)
        writeShort(0)
        output.flush()
    }

    private fun writeLocalHeader(e: Entry, name: ByteArray, extraLen: Int) {
        writeInt(LOCAL_SIG)
        writeShort(20) // version needed
        writeShort(0) // flags
        writeShort(e.method)
        writeShort(0) // mod time
        writeShort(33) // mod date
        writeInt(e.crc.toInt())
        writeInt(e.data.size)
        writeInt(e.rawSize)
        writeShort(name.size)
        writeShort(extraLen)
        output.write(name)
    }

    private fun writeCentralRecord(out: ByteArrayOutputStream, e: Entry, name: ByteArray, offset: Int) {
        writeInt(out, CENTRAL_SIG)
        writeShort(out, 798) // version made by
        writeShort(out, 20) // version needed
        writeShort(out, 0) // flags
        writeShort(out, e.method)
        writeShort(out, 0) // mod time
        writeShort(out, 33) // mod date
        writeInt(out, e.crc.toInt())
        writeInt(out, e.data.size)
        writeInt(out, e.rawSize)
        writeShort(out, name.size)
        writeShort(out, 0) // extra len
        writeShort(out, 0) // comment len
        writeShort(out, 0) // disk
        writeShort(out, 0) // internal attrs
        writeInt(out, 0) // external attrs
        writeInt(out, offset)
        out.write(name)
    }

    private fun writeShort(v: Int) {
        output.write(v and 0xFF)
        output.write((v shr 8) and 0xFF)
    }

    private fun writeInt(v: Int) {
        output.write(v and 0xFF)
        output.write((v shr 8) and 0xFF)
        output.write((v shr 16) and 0xFF)
        output.write((v shr 24) and 0xFF)
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private companion object {
        const val LOCAL_SIG = 0x04034B50
        const val CENTRAL_SIG = 0x02014B50
        const val EOCD_SIG = 0x06054B50
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        const val PAD_EXTRA_ID = 0xDD09

        /**
         * 内存页大小，未压缩原生库（lib/**/*.so）要求的最小对齐。
         * 4KB 为传统 ARM/x86 页大小；面向 16KB 页设备的构建应传入 16384。
         */
        const val PAGE_ALIGNMENT = 4096
    }
}
