package com.mcp.injector.apk

import java.io.File
import java.util.zip.ZipFile

/**
 * APK 清单解析器（任务 C 支撑文件）。
 *
 * 对齐反编译产物 ApkParser：直接解析二进制 AndroidManifest.xml（[AxmlReader]）产出 [ApkInfo]，
 * 组件名按反编译 resolveClassName 规则补全（`.` 开头→拼接包名；含 `.`→原样；否则→包名+.）。
 * launcher Activity 由 MAIN + LAUNCHER 的 intent-filter 判定。
 */
object ApkParser {

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"

    /** 解析 APK：读取 manifest 的 package/versionName/versionCode、uses-sdk、application 与组件。 */
    fun parse(apkFile: File): ApkInfo {
        val manifestBytes = ZipFile(apkFile).use { zip ->
            zip.getEntry(MANIFEST_ENTRY)?.let { zip.getInputStream(it).readBytes() }
        } ?: throw IllegalStateException("安装包缺少 AndroidManifest.xml")

        val root = AxmlReader.parse(manifestBytes)
        val pkg = root.attrs["package"]?.asString() ?: ""
        if (pkg.isEmpty()) throw IllegalStateException("manifest 缺少 package 属性")

        val versionName = root.attrs["versionName"]?.asString() ?: ""
        val versionCode = root.attrs["versionCode"]?.asInt()?.toLong() ?: 0L

        val usesSdk = root.child("uses-sdk")
        val minSdk = usesSdk?.attrs?.get("minSdkVersion")?.asInt() ?: 1
        val targetSdk = usesSdk?.attrs?.get("targetSdkVersion")?.asInt() ?: minSdk

        val application = root.child("application")
        val appLabel = application?.attrs?.get("label")?.asString()
        val applicationName = application?.attrs?.get("name")
            ?.asString()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolveClassName(pkg, it) }

        val activities = application?.children("activity").orEmpty()
            .map { readComponent("activity", pkg, it) }
        val aliases = application?.children("activity-alias").orEmpty()
            .map { readComponent("activity-alias", pkg, it) }
        val services = application?.children("service").orEmpty()
            .map { readComponent("service", pkg, it) }
        val receivers = application?.children("receiver").orEmpty()
            .map { readComponent("receiver", pkg, it) }
        val providers = application?.children("provider").orEmpty()
            .map { readComponent("provider", pkg, it) }
        val permissions = root.children("uses-permission")
            .mapNotNull { it.attrs["name"]?.asString() }
            .distinct()

        val launcher = (activities + aliases).firstOrNull { c ->
            c.actions.contains("android.intent.action.MAIN") &&
                c.categories.contains("android.intent.category.LAUNCHER")
        }

        return ApkInfo(
            packageName = pkg,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            appLabel = appLabel,
            applicationName = applicationName,
            launcherActivity = launcher?.name,
            activities = activities + aliases,
            services = services,
            receivers = receivers,
            providers = providers,
            permissions = permissions,
        )
    }

    /** 读取单个组件节点（对齐反编译 readComponent）。 */
    private fun readComponent(kind: String, pkg: String, node: AxmlReader.Element): ApkComponent {
        val rawName = node.attrs["name"]?.asString() ?: ""
        val resolved = resolveClassName(pkg, rawName)
        val exported = node.attrs["exported"]?.asBoolean()
        val permission = node.attrs["permission"]?.asString()
        val authorities = node.attrs["authorities"]?.asString()
        val targetActivity = if (kind == "activity-alias") {
            node.attrs["targetActivity"]?.asString()?.let { resolveClassName(pkg, it) }
        } else {
            null
        }

        val actions = ArrayList<String>()
        val categories = ArrayList<String>()
        val dataSchemes = ArrayList<String>()
        for (filter in node.children("intent-filter")) {
            for (a in filter.children("action")) {
                a.attrs["name"]?.asString()?.takeIf { it.isNotEmpty() }?.let { actions.add(it) }
            }
            for (c in filter.children("category")) {
                c.attrs["name"]?.asString()?.takeIf { it.isNotEmpty() }?.let { categories.add(it) }
            }
            for (d in filter.children("data")) {
                val scheme = d.attrs["scheme"]?.asString() ?: continue
                val host = d.attrs["host"]?.asString() ?: ""
                val pathPrefix = d.attrs["pathPrefix"]?.asString() ?: ""
                dataSchemes.add("$scheme://$host$pathPrefix")
            }
        }

        return ApkComponent(
            kind = kind,
            name = resolved,
            exported = exported,
            permission = permission,
            actions = actions.distinct(),
            categories = categories.distinct(),
            dataSchemes = dataSchemes.distinct(),
            authorities = authorities,
            targetActivity = targetActivity,
        )
    }

    /**
     * 类名补全（对齐反编译 resolveClassName）：
     * - 空串 → 原样
     * - 以 `.` 开头 → pkg + raw（raw 自带前导点）
     * - 含 `.` → 原样（已是全限定名）
     * - 否则 → pkg + "." + raw
     */
    fun resolveClassName(pkg: String, raw: String): String = when {
        raw.isEmpty() -> raw
        raw.startsWith(".") -> pkg + raw
        raw.contains(".") -> raw
        else -> "$pkg.$raw"
    }
}
