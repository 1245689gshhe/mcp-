package com.mcp.injector.crash

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 崩溃日志页 —— **纯原生 View 实现，完全不依赖 Compose / AppCompat**。
 *
 * 使用原生 View 的核心理由：若崩溃发生在 Compose 框架加载/首帧组合阶段，
 * 任何 Compose 界面（包括主界面与日志页自身）都会起不来。此页只用
 * LinearLayout/TextView/ScrollView 渲染，保证"无论什么情况都能看到崩溃日志"。
 */
class CrashLogActivity : Activity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("崩溃日志")
        buildRoot()
        render()
    }

    // ------------------------------------------------------------------
    // 原生 View 组装
    // ------------------------------------------------------------------

    private fun buildRoot() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(
            TextView(this).apply {
                text = "崩溃日志"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#146C62"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        toolbar.addView(
            Button(this).apply {
                text = "清空"
                setOnClickListener { confirmClear() }
            },
        )
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val listScroll = ScrollView(this).apply {
            addView(container)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        root.addView(toolbar)
        root.addView(listScroll)
        setContentView(root)
    }

    private fun render() {
        container.removeAllViews()
        val logs = CrashLogger.list(applicationContext)
        if (logs.isEmpty()) {
            container.addView(emptyHint("暂无崩溃日志"))
            return
        }
        container.addView(emptyHint("共 ${logs.size} 条，点击条目查看详情"))
        for (f in logs) {
            container.addView(logItem(f))
        }
    }

    private fun emptyHint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 8, 0, 16)
        setTextColor(Color.parseColor("#6F7976"))
    }

    private fun logItem(file: File): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            run { val m = dp(6); lp.setMargins(0, m, 0, m) }
            layoutParams = lp
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            isClickable = true
            // 点击直接复制完整日志，方便快速转发
            setOnClickListener {
                copyToClipboard(CrashLogger.content(file))
                Toast.makeText(this@CrashLogActivity, "已复制日志全文，可粘贴到聊天/文档发给开发者", Toast.LENGTH_LONG).show()
            }
            // 长按查看详情并二次确认复制/删除
            setOnLongClickListener {
                AlertDialog.Builder(this@CrashLogActivity)
                    .setTitle(file.name)
                    .setMessage(CrashLogger.content(file))
                    .setPositiveButton("复制") { _, _ ->
                        copyToClipboard(CrashLogger.content(file))
                        Toast.makeText(this@CrashLogActivity, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("删除") { _, _ ->
                        CrashLogger.delete(file)
                        render()
                    }
                    .setNegativeButton("清空全部", null)
                    .show()
                true
            }
        }
        card.addView(
            TextView(this).apply {
                text = file.name
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#146C62"))
            },
        )
        val head = firstMeaningfulLine(file)
        card.addView(
            TextView(this).apply {
                text = head.ifBlank { "点击复制全文，长按查看详情" }
                textSize = 13f
                maxLines = 2
                setTextColor(Color.parseColor("#3F4946"))
            },
        )
        card.addView(
            TextView(this).apply {
                text = "点击复制全文 · 长按查看详情"
                textSize = 11f
                setTextColor(Color.parseColor("#8A9390"))
            },
        )
        return card
    }

    /** 从日志文件里提取第一行有意义的异常摘要。 */
    private fun firstMeaningfulLine(file: File): String {
        val content = CrashLogger.content(file)
        val lines = content.lineSequence().toList()
        val method = lines.firstOrNull { it.startsWith("Caused by:") }
            ?: lines.firstOrNull { it.startsWith("java.") || it.startsWith("kotlin.") || it.startsWith("android.") }
            ?: lines.firstOrNull { it.contains("Exception") || it.contains("FATAL") }
        return method?.trim() ?: ""
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空崩溃日志")
            .setMessage("确定删除全部崩溃日志吗？")
            .setPositiveButton("确定") { _, _ ->
                CrashLogger.clearAll(applicationContext)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("crash", text))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}