package com.mcp.injector.bridge

import android.app.Activity
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 工具执行器（任务 B）：保留 5 种 target 类型
 * （start_activity / broadcast / start_service / content_call / method），
 * 并补系统限制适配：
 *  - 10s 超时：整次执行在独立线程中运行，超时中断并回传错误
 *  - 后台 Activity 启动限制：优先使用前台 Activity 上下文启动；仍被拒则捕获 SecurityException 回传
 *  - 隐式广播 → 显式：Android O 起隐式广播受限，发送前解析为显式 component
 *  - 所有失败（超时 / 系统限制 / 反射异常）统一捕获为 isError=true 回传，不回抛
 */
class ToolExecutor(context: Context) {

    /** 执行结果：错误信息统一捕获并标记 isError。 */
    class ExecResult(val text: String, val isError: Boolean) {
        companion object {
            fun ok(text: String) = ExecResult(text, false)
            fun error(text: String) = ExecResult(text, true)
        }
    }

    private val appContext: Context = context.applicationContext

    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-tool-exec").apply { isDaemon = true }
    }

    /** 统一入口：按 kind 分派，整体 10s 超时，错误捕获回传。 */
    fun execute(tool: ToolRegistry.Tool, args: JSONObject?): ExecResult {
        val target = tool.target
        val callArgs = args ?: JSONObject()
        val kind = target.optString("kind", "start_activity")
        val future = pool.submit<ExecResult> {
            when (kind) {
                "broadcast" -> broadcast(target, callArgs)
                "start_service" -> startService(target, callArgs)
                "content_call" -> contentCall(target, callArgs)
                "method" -> method(tool, target, callArgs)
                else -> startActivity(target, callArgs)
            }
        }
        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            ExecResult.error("${tool.name} 执行超时(10s)")
        } catch (e: Exception) {
            val cause = e.cause ?: e
            ExecResult.error("${tool.name} 执行失败: $cause")
        }
    }

    // ---- start_activity ----
    private fun startActivity(target: JSONObject, args: JSONObject): ExecResult {
        val intent = buildIntent(target, args)
        if (intent.component == null && intent.action == null && intent.data == null) {
            return ExecResult.error("target 缺少 action/component/uri")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            // 后台 Activity 启动限制适配：优先用前台 Activity 上下文启动
            val activityCtx = foregroundActivityContext()
            if (activityCtx != null) {
                activityCtx.startActivity(intent)
            } else {
                appContext.startActivity(intent)
            }
            ExecResult.ok("activity 已启动")
        } catch (e: SecurityException) {
            // Android 10+ 后台 Activity 启动限制：捕获并回传错误信息
            ExecResult.error("后台启动 Activity 被系统限制(SecurityException): $e")
        } catch (e: Exception) {
            ExecResult.error("activity 启动失败: $e")
        }
    }

    // ---- broadcast ----
    private fun broadcast(target: JSONObject, args: JSONObject): ExecResult {
        val intent = buildIntent(target, args)
        // 显式广播（只指定 component）无需 action，只有 action/component 都为空才算缺少
        if (intent.action == null && intent.component == null) {
            return ExecResult.error("broadcast 缺少 action/component")
        }
        // 隐式广播 → 显式：能解析到接收者时一律转为显式 component
        resolveExplicit(intent)
        appContext.sendBroadcast(intent)
        // 发送结果描述需同时覆盖 action 为空、component 非空的情况，且不能打出 "null"
        val action = intent.action
        val comp = intent.component
        val desc = when {
            action != null && comp != null -> "$action -> ${comp.flattenToShortString()}"
            action != null -> action
            comp != null -> comp.flattenToShortString()
            else -> "显式广播"
        }
        return ExecResult.ok("广播已发送: $desc")
    }

    private fun resolveExplicit(intent: Intent) {
        try {
            val matches = appContext.packageManager.queryBroadcastReceivers(intent, 0)
            if (!matches.isNullOrEmpty()) {
                val info = matches[0].activityInfo ?: return
                intent.component = ComponentName(info.packageName, info.name)
            }
        } catch (t: Throwable) {
            // 解析失败保持隐式发送（部分系统广播仍允许）
        }
    }

    // ---- start_service ----
    private fun startService(target: JSONObject, args: JSONObject): ExecResult {
        val intent = buildIntent(target, args)
        return try {
            val comp = appContext.startService(intent)
            if (comp == null) {
                ExecResult.error("服务启动失败，可能未在清单声明")
            } else {
                ExecResult.ok("服务已启动: ${comp.flattenToShortString()}")
            }
        } catch (e: IllegalStateException) {
            // Android O 后台启动服务限制
            ExecResult.error("后台启动服务被系统限制: $e")
        } catch (e: Exception) {
            ExecResult.error("服务启动失败: $e")
        }
    }

    // ---- content_call ----
    private fun contentCall(target: JSONObject, args: JSONObject): ExecResult {
        try {
            val authorities = target.optString("authorities", "")
            if (authorities.isEmpty()) return ExecResult.error("content_call 缺少 authorities")
            val uri = Uri.parse("content://$authorities")
            val resolver: ContentResolver = appContext.contentResolver
            val contentMethod = target.optString("contentMethod", "call")
            when (contentMethod) {
                "call" -> {
                    val built = BundleBuilder.build(target, args)
                    val methodName = target.optString("method", args.optString("methodName", ""))
                    val arg = target.optString("arg", "")
                    val call = if (Build.VERSION.SDK_INT >= 29) {
                        resolver.call(uri, methodName, arg, built.bundle)
                    } else {
                        resolver.call(authorities, methodName, arg, built.bundle)
                    }
                    return ExecResult.ok("provider.call 返回: $call")
                }
                "query" -> {
                    val cursor = resolver.query(uri, null, null, null, null)
                    if (cursor == null) return ExecResult.error("query 返回空")
                    try {
                        if (!cursor.moveToFirst()) return ExecResult.ok("query 无数据")
                        val sb = StringBuilder("{")
                        val cols = minOf(cursor.columnCount, 12)
                        for (i in 0 until cols) {
                            if (i > 0) sb.append(",")
                            sb.append("\"").append(cursor.getColumnName(i)).append("\":\"")
                                .append((cursor.getString(i) ?: "").toString()).append("\"")
                        }
                        sb.append("}")
                        return ExecResult.ok(sb.toString())
                    } finally {
                        cursor.close()
                    }
                }
                "insert" -> return ExecResult.ok("insert 返回: ${resolver.insert(uri, null)}")
                "update" -> return ExecResult.ok("update 影响行数: ${resolver.update(uri, null, null, null)}")
                "delete" -> return ExecResult.ok("delete 影响行数: ${resolver.delete(uri, null, null)}")
                else -> return ExecResult.error("不支持的 contentMethod: $contentMethod")
            }
        } catch (e: Exception) {
            return ExecResult.error("content_call 执行失败: $e")
        }
    }

    // ---- method（反射调用） ----
    private fun method(tool: ToolRegistry.Tool, target: JSONObject, args: JSONObject): ExecResult {
        try {
            val methodClass = target.optString("methodClass", "")
            val methodName = target.optString("methodName", "")
            if (methodClass.isEmpty() || methodName.isEmpty()) {
                return ExecResult.error("method 缺少 methodClass/methodName")
            }
            val clazz = Class.forName(methodClass)
            var best: java.lang.reflect.Method? = null
            for (m in clazz.declaredMethods) {
                if (m.name == methodName && (best == null || m.parameterTypes.size >= best.parameterTypes.size)) {
                    best = m
                }
            }
            val method = best ?: return ExecResult.error("找不到方法: $methodClass#$methodName")
            method.isAccessible = true

            var receiver: Any? = null
            if (!Modifier.isStatic(method.modifiers)) {
                val ctor = clazz.getDeclaredConstructor()
                ctor.isAccessible = true
                receiver = ctor.newInstance()
            }

            val argTypes = ArrayList<String>()
            try {
                val arr = target.optJSONArray("methodArgTypes")
                if (arr != null) {
                    for (i in 0 until arr.length()) argTypes.add(arr.optString(i))
                }
            } catch (t: Throwable) {
                // 忽略类型列表解析错误
            }

            val params = arrayOfNulls<Any>(method.parameterTypes.size)
            // 属性名兜底：提示词要求模型把入参命名为 p0/p1…，但模型未必遵守。
            // 这里额外按 inputSchema.properties 的声明顺序取同名实参，避免参数全部落空。
            val propNames = schemaPropertyNames(tool.inputSchema)
            for (i in params.indices) {
                val type = if (i < argTypes.size) argTypes[i] else "string"
                params[i] = coerce(argAt(args, i, propNames, methodName), type)
            }
            return ExecResult.ok(method.invoke(receiver, *params)?.toString() ?: "null")
        } catch (e: Exception) {
            return ExecResult.error("method 执行失败: $e")
        }
    }

    /**
     * 取第 [index] 个形参的实参值，按优先级：
     * 1. `p{index}`（提示词约定的命名）；
     * 2. inputSchema.properties 中按声明顺序的第 index 个属性名（模型惯用的业务命名）；
     * 3. index == 0 时退回方法名同名的参数（单参方法的常见写法）。
     * 都没有则返回 null（由 [coerce] 交给反射，类型不符时抛可读异常）。
     */
    private fun argAt(args: JSONObject, index: Int, propNames: List<String>, methodName: String): Any? {
        args.opt("p$index")?.let { return it }
        if (index < propNames.size) {
            val key = propNames[index]
            if (args.has(key)) return args.opt(key)
        }
        if (index == 0 && args.has(methodName)) return args.opt(methodName)
        return null
    }

    /** 读 inputSchema.properties 的键顺序（org.json 保持插入顺序，即模型给出的声明顺序）。 */
    private fun schemaPropertyNames(schema: JSONObject): List<String> {
        val props = schema.optJSONObject("properties") ?: return emptyList()
        val out = ArrayList<String>()
        val keys = props.keys()
        while (keys.hasNext()) out.add(keys.next())
        return out
    }

    private fun buildIntent(target: JSONObject, args: JSONObject): Intent {
        val intent = Intent()
        target.optString("action", "").takeIf { it.isNotEmpty() }?.let { intent.action = it }
        resolveComponent(target.optString("component", ""))?.let { intent.component = it }
        target.optString("uri", "").takeIf { it.isNotEmpty() }?.let {
            intent.data = Uri.parse(it)
        }
        val built = BundleBuilder.build(target, args)
        if (built.bundle != null) intent.putExtras(built.bundle)
        return intent
    }

    /**
     * 归一化 component 字符串 → ComponentName。
     *
     * 提示词让模型填「完整类名」（如 com.example.app.MainActivity），但
     * [ComponentName.unflattenFromString] 只认 "pkg/cls" 格式：字符串里没有 `/` 时一律返回
     * null。裸类名被解析成 null 后，start_activity 会误报「target 缺少 action/component/uri」、
     * broadcast 会误报「broadcast 缺少 action/component」——工具全废。
     *
     * 这里改为按候选顺序逐个用 PackageManager 校验（注入的 bridge 跑在目标应用进程内，
     * 本应用组件一定可见），取第一个真实存在的组合；都解析不到时仍返回最可能的一个，
     * 把失败交给系统抛出，而不是让 component 变成 null 造成误报。
     */
    private fun resolveComponent(raw: String): ComponentName? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val self = appContext.packageName
        // 清单相对写法 ".MainActivity"：直接补应用包名
        if (s.startsWith(".")) return ComponentName(self, self + s)
        val candidates = componentCandidates(s, self)
        return candidates.firstOrNull { componentExists(it) } ?: candidates.firstOrNull()
    }

    /** 候选组合（按可能性降序）：原生 pkg/cls → (应用包名, 完整类名) → 自右向左的 pkg/cls 切分。 */
    private fun componentCandidates(s: String, self: String): List<ComponentName> {
        val out = ArrayList<ComponentName>()
        ComponentName.unflattenFromString(s)?.let { out.add(it) }
        if (s.contains('.')) {
            // 清单里组件的规范形式：包名 = 应用包名，类名 = 完整类名
            // （android:name 无论是 ".Foo" 还是 "com.x.ui.Foo"，PackageManager 存的都是这个组合）
            out.add(ComponentName(self, s))
            var idx = s.lastIndexOf('.')
            while (idx > 0) {
                out.add(ComponentName(s.substring(0, idx), s.substring(idx + 1)))
                idx = s.lastIndexOf('.', idx - 1)
            }
        } else {
            out.add(ComponentName(self, "$self.$s"))
        }
        return out
    }

    /** 该组合是否真实存在（activity/service/receiver 任一）。 */
    private fun componentExists(cn: ComponentName): Boolean {
        val pm = appContext.packageManager
        return runCatching { pm.getActivityInfo(cn, 0) }.isSuccess ||
            runCatching { pm.getServiceInfo(cn, 0) }.isSuccess ||
            runCatching { pm.getReceiverInfo(cn, 0) }.isSuccess
    }

    private fun coerce(value: Any?, type: String): Any? {
        if (value == null) return null
        return when (type) {
            "int" -> numberValue(value).toInt()
            "long" -> numberValue(value).toLong()
            "float" -> numberValue(value).toFloat()
            "double" -> numberValue(value).toDouble()
            "bool" -> if (value is Boolean) {
                value
            } else {
                when (value.toString().trim().lowercase()) {
                    "1", "true" -> true
                    "0", "false" -> false
                    else -> throw IllegalArgumentException("非法 bool 值: $value")
                }
            }
            else -> value.toString()
        }
    }

    /**
     * 数值容错转换：Number 直接透传；字符串同时接受整数与小数写法
     * （"5"、"5.0"、"5e2"）。旧实现用 `value.toString().toInt()`，
     * 模型把数字写成 "5.0" 时会抛 NumberFormatException，整次调用失败。
     */
    private fun numberValue(value: Any): Number {
        if (value is Number) return value
        val s = value.toString().trim()
        return s.toLongOrNull() ?: s.toDoubleOrNull()
            ?: throw IllegalArgumentException("非法数值: $value")
    }

    /** 反射查找未销毁的 Activity 实例，用于绕过后台 Activity 启动限制。 */
    private fun foregroundActivityContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getMethod("currentActivityThread").invoke(null) ?: return null
            val mActivities = atClass.getDeclaredField("mActivities")
            mActivities.isAccessible = true
            val map = mActivities.get(thread) ?: return null
            val size = map.javaClass.getMethod("size").invoke(map) as? Int ?: return null
            for (i in 0 until size) {
                val value = map.javaClass.getMethod("valueAt", Int::class.javaPrimitiveType).invoke(map, i) ?: continue
                val activityField = value.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                val activity = activityField.get(value) as? Activity ?: continue
                if (!activity.isFinishing) return activity
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** 按 target.extras 的类型声明把 args 同名键打包成 Bundle。 */
    private class BundleBuilder private constructor() {

        class Built(val bundle: Bundle?)

        companion object {
            fun build(target: JSONObject, args: JSONObject): Built {
                val bundle = Bundle()
                val extras = target.optJSONObject("extras") ?: return Built(bundle)
                val keys = extras.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val type = extras.optString(key, "string")
                    if (!args.has(key)) continue
                    try {
                        when (type) {
                            "int" -> bundle.putInt(key, args.getInt(key))
                            "long" -> bundle.putLong(key, args.getLong(key))
                            "float" -> bundle.putFloat(key, args.getDouble(key).toFloat())
                            "double" -> bundle.putDouble(key, args.getDouble(key))
                            "bool" -> bundle.putBoolean(key, args.getBoolean(key))
                            else -> bundle.putString(key, args.optString(key, ""))
                        }
                    } catch (t: Throwable) {
                        // 单个字段类型不符时跳过
                    }
                }
                return Built(bundle)
            }
        }
    }
}
