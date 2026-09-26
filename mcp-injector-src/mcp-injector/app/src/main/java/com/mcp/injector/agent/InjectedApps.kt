package com.mcp.injector.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest

/**
 * 已注入应用记录（任务 C 支撑文件）。
 *
 * 指纹识别三来源之一「注入历史」的宿主侧持久化：
 * - [InjectedAppsStore]：`injected_apps.json` 保存每次注入的历史
 *   （包名、签名指纹、installId、端口、模块——契约字段）；
 * - [InjectedAppScanner]：「与注入器同签名的已安装应用」扫描
 *   （PackageManager 签名比较，即指纹识别第二来源）；
 * - 第三来源「AgentReceiver 广播探测」见 [AgentProbe]。
 */
data class InjectedApp(
    /** 注入指纹：宿主生成的 installId（扫描来源为 "scan:<pkg>" 占位）。 */
    val installId: String,
    val packageName: String,
    val appName: String,
    val versionName: String,
    /** 签名指纹（SHA-256 hex）：与注入器同签名的应用可被扫描识别。 */
    val signatureFingerprint: String,
    /** 注入端口（扫描来源未知时为 0，由广播探测补全）。 */
    val port: Int,
    val modules: List<String>,
    val injectedAt: Long,
    /** "history"（注入历史）或 "scan"（同签名扫描）。 */
    val source: String,
    /** 注入产物 APK 相对 filesDir 的路径（可用于安装/复检）。 */
    val outputRelative: String? = null,
    /** 注入源 APK 相对 filesDir 的路径（重新注入时复用）。 */
    val sourceRelative: String? = null,
    /** 注入策略（hook / service），诊断回传再注入时参考。 */
    val strategy: String? = null,
    /** 注入时是否成功 hook 启动类（clinit 策略才有意义）。 */
    val hookable: Boolean = false,
    /** 注入入口方式：provider（默认，不改目标 dex）/ clinit（<clinit> 插桩）。 */
    val entryPoint: String? = null,
    /** 注入时是否开启了启动提示。 */
    val toastOnBoot: Boolean = true,
)

/** 注入历史仓库：filesDir/injected_apps.json。 */
class InjectedAppsStore(private val context: Context) {

    private fun file(): File =
        File(context.filesDir, "injected_apps.json").apply { parentFile?.mkdirs() }

    @Synchronized
    fun loadAll(): List<InjectedApp> {
        if (!file().exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file().readText())
            val arr = root.optJSONArray("apps") ?: return emptyList()
            (0 until arr.length()).mapNotNull { parse(it, arr.optJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.injectedAt }
    }

    @Synchronized
    fun saveAll(apps: List<InjectedApp>) {
        val arr = JSONArray()
        for (app in apps) arr.put(toJson(app))
        val target = file()
        // 原子写：先写同目录临时文件，再 rename 覆盖目标。避免直接覆盖时写入
        // 中途被杀/异常留下半截或空文件，进而被 loadAll 的 runCatching 解析失败
        // 静默吞成 emptyList，导致全部注入历史丢失。
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileWriter(tmp).use { it.write(JSONObject().put("apps", arr).toString(2)) }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // 极少数文件系统 renameTo 可能失败，退化为复制后删除临时文件，避免残留 tmp。
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 按包名去重插入（历史优先于扫描，覆盖旧记录）。 */
    @Synchronized
    fun upsert(app: InjectedApp): List<InjectedApp> {
        val all = loadAll().filter { it.packageName != app.packageName } + app
        saveAll(all)
        return all
    }

    @Synchronized
    fun remove(packageName: String): List<InjectedApp> {
        val all = loadAll().filter { it.packageName != packageName }
        saveAll(all)
        return all
    }

    fun find(packageName: String): InjectedApp? =
        loadAll().firstOrNull { it.packageName == packageName }

    /** 宿主自身签名指纹（SHA-256 hex）：同时是注入产物（重签名）与「同签名扫描」的基准。 */
    fun sigFingerprint(): String? = fingerprintOf(context, context.packageName)

    // ---- 序列化 ----

    private fun toJson(app: InjectedApp): JSONObject = JSONObject()
        .put("installId", app.installId)
        .put("packageName", app.packageName)
        .put("appName", app.appName)
        .put("versionName", app.versionName)
        .put("signatureFingerprint", app.signatureFingerprint)
        .put("port", app.port)
        .put("modules", JSONArray(app.modules))
        .put("injectedAt", app.injectedAt)
        .put("source", app.source)
        .put("outputRelative", app.outputRelative ?: JSONObject.NULL)
        .put("sourceRelative", app.sourceRelative ?: JSONObject.NULL)
        .put("strategy", app.strategy ?: JSONObject.NULL)
        .put("hookable", app.hookable)
        .put("entryPoint", app.entryPoint ?: JSONObject.NULL)
        .put("toastOnBoot", app.toastOnBoot)

    private fun parse(idx: Int, obj: JSONObject?): InjectedApp? {
        obj ?: return null
        val pkg = obj.optString("packageName", "")
        if (pkg.isEmpty()) return null
        val arr = obj.optJSONArray("modules")
        val modules = if (arr == null) {
            emptyList()
        } else {
            (0 until arr.length()).map { arr.optString(it) }
        }
        return InjectedApp(
            installId = obj.optString("installId", ""),
            packageName = pkg,
            appName = obj.optString("appName", pkg),
            versionName = obj.optString("versionName", ""),
            signatureFingerprint = obj.optString("signatureFingerprint", ""),
            port = obj.optInt("port", 0),
            modules = modules,
            injectedAt = obj.optLong("injectedAt", 0L),
            source = obj.optString("source", "history"),
            outputRelative = obj.optString("outputRelative").takeIf { it.isNotEmpty() },
            sourceRelative = obj.optString("sourceRelative").takeIf { it.isNotEmpty() },
            strategy = obj.optString("strategy").takeIf { it.isNotEmpty() },
            hookable = obj.optBoolean("hookable", false),
            entryPoint = obj.optString("entryPoint").takeIf { it.isNotEmpty() },
            toastOnBoot = obj.optBoolean("toastOnBoot", true),
        )
    }

    companion object {
        /** 计算某安装包签名证书 SHA-256 hex；无签名/异常返回 null。 */
        @Suppress("DEPRECATION")
        fun fingerprintOf(context: Context, packageName: String): String? = runCatching {
            val pm = context.packageManager
            val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+：使用 GET_SIGNING_CERTIFICATES + signingInfo
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?: return null
                val signingInfo = info.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.firstOrNull()
                } else {
                    signingInfo.signingCertificateHistory.firstOrNull()
                }
            } else {
                // API < 28：回退到废弃的 GET_SIGNATURES + PackageInfo.signatures
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    ?: return null
                info.signatures?.firstOrNull()
            }
            val cert = signer ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}

/** 同签名扫描：找出与注入器同签名的已安装应用（指纹识别来源二）。 */
object InjectedAppScanner {

    /**
     * 扫描已安装应用中与宿主同签名的应用，产出 source="scan" 的 [InjectedApp] 列表。
     * 已存在于历史中的包名跳过（由历史记录覆盖展示）。
     */
    fun scan(context: Context, store: InjectedAppsStore): List<InjectedApp> {
        val hostFp = store.sigFingerprint() ?: return emptyList()
        val existing = store.loadAll().map { it.packageName }.toSet()
        val pm = context.packageManager
        val out = ArrayList<InjectedApp>()
        for (pi in pm.getInstalledPackages(0)) {
            val pkg = pi.packageName ?: continue
            if (pkg == context.packageName) continue
            if (pkg in existing) continue
            val fp = InjectedAppsStore.fingerprintOf(context, pkg) ?: continue
            if (fp != hostFp) continue
            val label = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
            out.add(
                InjectedApp(
                    installId = "scan:$pkg",
                    packageName = pkg,
                    appName = label,
                    versionName = pi.versionName ?: "",
                    signatureFingerprint = fp,
                    port = 0,
                    modules = emptyList(),
                    injectedAt = System.currentTimeMillis(),
                    source = "scan",
                ),
            )
        }
        return out.sortedByDescending { it.injectedAt }
    }
}
