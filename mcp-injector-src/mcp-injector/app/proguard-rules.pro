# 注入/桥接类会被动态加载进目标 APK，宿主侧保持类名，防止混淆破坏 dexlib2 引用
-keep class com.mcp.injector.bridge.** { *; }
-keep class com.mcp.injector.apk.** { *; }
