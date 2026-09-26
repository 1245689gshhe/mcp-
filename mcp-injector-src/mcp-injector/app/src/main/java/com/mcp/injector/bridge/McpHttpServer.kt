package com.mcp.injector.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MCP HTTP 桥接服务（任务 B）。
 *
 * 纯 Java 风格自包含实现（ServerSocket 线程 + org.json，不依赖宿主/Compose 类）：
 *  - MCP 协议：GET /health、GET /mcp（服务信息）、POST /mcp（JSON-RPC）、GET /sse（sse 模式）
 *  - 官方模块控制端点（挂载于此，路径 /mcp 下）：
 *    GET  /mcp/status | /mcp/tools | /mcp/diagnose
 *    POST /mcp/config {"port": N}
 *  - 供 Bridge 调用的 stop() / isRunning() / port()
 */
class McpHttpServer(
    private val config: BridgeConfig,
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val official: OfficialModule,
) {

    companion object {
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val SERVER_NAME = "mcp-bridge"
        private const val SERVER_VERSION = "0.9.2"
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val MAX_HEADER = 64 * 1024
    }

    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-http").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        serverSocket = ServerSocket(config.port, 16, InetAddress.getByName(config.host))
        val t = Thread({ acceptLoop() }, "mcp-accept")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (t: Throwable) {
        }
        serverSocket = null
        // 关闭线程池，避免 restart() 反复创建实例导致线程泄漏。
        // 使用 shutdown() 而非 shutdownNow()：stop() 可能正由处理 POST /mcp/config
        // 的池内线程间接调用（restart 触发），shutdownNow() 会自我中断，导致当前
        // 响应无法写出。shutdown() 只停止接收新任务，已提交/运行中的任务照常跑完。
        // accept 线程会因上面的 ServerSocket.close() 而自然退出（守护线程，不阻塞进程）。
        try {
            pool.shutdown()
        } catch (t: Throwable) {
        }
    }

    fun isRunning(): Boolean = serverSocket?.isClosed() == false

    fun port(): Int = serverSocket?.localPort ?: config.port

    private fun acceptLoop() {
        while (serverSocket?.isClosed() == false) {
            val ss = serverSocket ?: return
            try {
                val socket = ss.accept()
                pool.execute {
                    try {
                        socket.soTimeout = 30_000
                        handle(socket)
                    } catch (t: Throwable) {
                        // 单连接错误不影响服务
                    } finally {
                        try {
                            socket.close()
                        } catch (t: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                if (serverSocket?.isClosed() == true) return
            }
        }
    }

    /**
     * 从原始 InputStream 逐字节读取请求头，直到出现空行 "\r\n\r\n" 为止（返回文本含该结尾）。
     * 只依赖 java.io，不预读后续字节，从而保证 body 不会被提前消费。
     */
    private fun readHeaders(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var b0 = -1
        var b1 = -1
        var b2 = -1
        var b3 = -1
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            b0 = b1
            b1 = b2
            b2 = b3
            b3 = b
            // 末尾四个字节为 \r\n\r\n（13,10,13,10）则请求头结束
            if (b0 == 13 && b1 == 10 && b2 == 13 && b3 == 10) break
            // 畸形/超长请求头保护：超过上限立即停止读取，避免 ByteArrayOutputStream 无限增长 OOM
            if (out.size() >= MAX_HEADER) break
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 从同一个 InputStream 精确读取 contentLength 个「字节」并按 UTF-8 解码。
     * 无 body（contentLength <= 0）时返回空串。
     */
    private fun readBody(input: InputStream, contentLength: Int): String {
        if (contentLength <= 0) return ""
        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(buf, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun handle(socket: Socket) {
        // 直接用原始 InputStream：绝不能用 BufferedReader（会预读缓冲），否则
        // 请求体字节会被提前吞掉/错位。Content-Length 是「字节数」，必须按字节读 body，
        // 否则含多字节 UTF-8（如中文参数）时字符数 < 字节数会导致循环一直等数据、
        // 最终 SocketTimeoutException（30s）使请求挂死。
        val input: InputStream = socket.getInputStream()
        val headerText = readHeaders(input)
        if (headerText.isEmpty()) return
        val headerLines = headerText.split("\r\n")
        val requestLine = headerLines.firstOrNull() ?: return
        if (requestLine.isEmpty()) return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        var path = parts[1]
        val q = path.indexOf('?')
        if (q >= 0) path = path.substring(0, q)

        // 解析请求头（Content-Length 用于限制请求体）
        var contentLength = 0
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (key == "content-length") {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }
        if (contentLength > MAX_BODY) {
            writeJson(socket, 413, error(0, "请求体过大"))
            return
        }
        // 从同一个 InputStream 精确读取 contentLength 个「字节」，再按 UTF-8 解码
        val body = readBody(input, contentLength)

        when {
            method == "GET" && path == "/health" -> writeText(socket, 200, "ok")

            method == "GET" && path == "/sse" && config.mode == "sse" -> serveSse(socket)

            method == "GET" && path == "/mcp" -> {
                val info = JSONObject()
                    .put("name", SERVER_NAME)
                    .put("version", SERVER_VERSION)
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("mode", config.mode)
                    .put("tools", registry.list().size)
                writeJson(socket, 200, info)
            }

            method == "POST" && path == "/mcp" -> {
                val rpc = dispatchRpc(body)
                if (rpc == null) {
                    // 通知类请求（无 id，如 notifications/initialized）：按 JSON-RPC/MCP 约定
                    // 返回 202 且响应体为空，不写任何 JSON body。
                    writeEmpty(socket, 202)
                } else {
                    writeJson(socket, 200, rpc)
                }
            }

            // 官方模块控制端点 /mcp 下
            method == "GET" && path == "/mcp/status" -> writeJson(socket, 200, official.status())
            method == "GET" && path == "/mcp/tools" -> writeJson(socket, 200, official.toolsStatus())
            method == "GET" && path == "/mcp/diagnose" -> writeJson(socket, 200, official.diagnose())
            // 目标 App 崩溃日志（注入后闪退排查证据）
            method == "GET" && path == "/mcp/crash" -> writeJson(socket, 200, official.crashInfo())
            method == "POST" && path == "/mcp/crash/clear" -> writeJson(socket, 200, official.clearCrash())
            method == "POST" && path == "/mcp/config" -> writeJson(socket, 200, official.applyConfigBody(body))

            else -> writeJson(socket, 404, error(0, "未知路径: $path"))
        }
    }

    private fun dispatchRpc(body: String): JSONObject? {
        return try {
            val req = JSONObject(body)
            val id = req.opt("id")
            val method = req.optString("method", "")
            val params = req.optJSONObject("params") ?: JSONObject()

            val result: JSONObject? = when (method) {
                "initialize" -> initialize()
                "ping" -> JSONObject()
                "tools/list" -> toolsList()
                "tools/call" -> toolsCall(params)
                "registry/list" -> toolsList()
                // 无内置鉴权（authToken 已移除），直接更新
                "registry/update" -> {
                    registry.update(params.optString("tools", ""))
                    toolsList()
                }
                else -> {
                    if (method.startsWith("notifications/")) return null
                    return withId(error(-32601, "method not found: $method"), id)
                }
            }

            val resp = JSONObject()
            resp.put("jsonrpc", "2.0")
            resp.put("id", if (id == null) JSONObject.NULL else id)
            resp.put("result", result)
            resp
        } catch (t: Throwable) {
            error(-32700, "解析失败: $t")
        }
    }

    private fun initialize(): JSONObject {
        val caps = JSONObject()
        val toolsCap = JSONObject().put("listChanged", true)
        caps.put("tools", toolsCap)
        val serverInfo = JSONObject()
            .put("name", SERVER_NAME)
            .put("version", SERVER_VERSION)
        return JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", caps)
            .put("serverInfo", serverInfo)
    }

    private fun toolsList(): JSONObject {
        val arr = JSONArray()
        for (tool in registry.list()) {
            arr.put(tool.describe())
        }
        return JSONObject().put("tools", arr)
    }

    private fun toolsCall(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments")
        val tool = registry.find(name)
        val out = JSONObject()
        if (tool == null) {
            out.put("isError", true)
            out.put("content", singleText("未注册的工具: $name"))
            return out
        }
        val result = executor.execute(tool, args)
        out.put("isError", result.isError)
        out.put("content", singleText(result.text))
        return out
    }

    private fun singleText(text: String): JSONArray {
        val arr = JSONArray()
        val item = JSONObject()
        item.put("type", "text")
        item.put("text", text)
        arr.put(item)
        return arr
    }

    private fun serveSse(socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
        out.write("event: endpoint\ndata: /mcp\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
        val ping = ": ping\n\n".toByteArray(Charsets.UTF_8)
        while (!socket.isClosed) {
            try {
                Thread.sleep(15_000L)
                out.write(ping)
                out.flush()
            } catch (t: Throwable) {
                return
            }
        }
    }

    private fun writeJson(socket: Socket, status: Int, json: JSONObject) {
        writeBytes(socket, status, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeText(socket: Socket, status: Int, text: String) {
        writeBytes(socket, status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(socket: Socket, status: Int, contentType: String, body: ByteArray) {
        val out: OutputStream = socket.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ")
            .append(status).append(" ").append(reason(status))
            .append("\r\nContent-Type: ").append(contentType)
            .append("\r\nContent-Length: ").append(body.size)
            .append("\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    /** 写出无响应体的状态响应（如通知类请求的 202）。 */
    private fun writeEmpty(socket: Socket, status: Int) {
        val out: OutputStream = socket.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ")
            .append(status).append(" ").append(reason(status))
            .append("\r\nContent-Length: 0")
            .append("\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 按状态码返回正确的 HTTP reason phrase。 */
    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }

    private fun error(code: Int, message: String): JSONObject {
        val json = JSONObject()
        try {
            json.put("code", code)
            json.put("message", message)
        } catch (t: Throwable) {
        }
        return json
    }

    private fun withId(json: JSONObject, id: Any?): JSONObject {
        try {
            json.put("jsonrpc", "2.0")
            json.put("id", if (id == null) JSONObject.NULL else id)
        } catch (t: Throwable) {
        }
        return json
    }
}
