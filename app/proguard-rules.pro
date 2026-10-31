# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# libxposed 通过 META-INF/xposed/java_init.list 中的类名字符串加载模块入口；
# 允许入口类混淆时，需要同步改写 java_init.list，避免 release 裁剪后模块失效。
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation class io.github.mangi.eta.ModuleMain {
    public <init>();
}

# libxposed service 通过静态调用和 manifest provider 接入，交给 R8/Android 默认规则保留可达代码。
-dontwarn io.github.libxposed.service.**

# 配置 key 是字符串常量并通过静态调用访问，不需要保留类名或成员名。

# ── Release 日志策略 ────────────────────────────────────────────────────────
# 仅删除 Eta 自有代码中的 Android VERBOSE/DEBUG 调用；INFO/WARN/ERROR 必须保留，
# 第三方依赖的日志策略由依赖自身决定。
-maximumremovedandroidloglevel 3 class io.github.mangi.eta.** { *; }

# XposedModule.log 不是 android.util.Log，R8 无法通过上面的规则识别。
# debug supplier 是纯观察 API；禁止在 supplier 内执行任何业务副作用。
-assumenosideeffects interface io.github.mangi.eta.core.AgentLogger {
    public abstract void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.mangi.eta.core.AndroidAgentLogger {
    public void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.mangi.eta.core.ModuleLogger {
    public void debug(kotlin.jvm.functions.Function0);
}

# ── 序列化与网络依赖 ─────────────────────────────────────────────────────────
# DataStore、kotlinx.serialization、OkHttp 与 Okio 均自带精确的 consumer rules；
# 不在 App 层重复保留整个类或包，避免阻断裁剪、内联和混淆。
# Nimbus 内嵌 Gson 携带此 CLASS 级静态检查注解，运行时不依赖它。
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue

# 保留源码与行号属性，便于使用 release mapping 还原线上堆栈。
-keepattributes SourceFile,LineNumberTable

# JNI looks up configuration fields/classes by their upstream names.
-keep class com.k2fsa.sherpa.ncnn.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM（本地多模态 Embedding 运行时）：JNI 与反射按上游类名查找引擎/配置类。
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# app_process 按字面类名启动只读探针，R8 看不到 shell 字符串里的引用。
# 保留 CLI 入口 public static void main(String[])，以及探针会执行到的 vd 类，避免 release 裁剪或改名。
-keep class vd.android.ReadOnlyProbe {
    public static void main(java.lang.String[]);
}
-keep class vd.android.AndroidBridge { *; }
-keep class vd.android.AndroidReadOnlyBackend { *; }
-keep class vd.android.AndroidReadOnlyBackend$* { *; }
-keep class vd.close.TaskHandle { *; }
-keep class vd.close.ActionResult { *; }
-keep class vd.close.CloseSurface { *; }

# Root app_process entrypoints packaged in the app APK.
-keep class vd.runtime.** { *; }

# 虚拟屏 Root 特权桥：由 Root shell 以 app_process + CLASSPATH 反射全限定名启动，
# 混淆改名后 VirtualDisplayController 将找不到入口类。
-keep class io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain {
    public static void main(java.lang.String[]);
}

# ── Navigation Compose 类型安全路由 ───────────────────────────────────────────
# 路由类与路由参数类型（如枚举 NewProviderType）由 Navigation 在运行时按
# 全限定名反射解析；R8 混淆改名后会抛
# "Cannot find class with name <fqcn>"（启动即闪退）。保留整个路由包的类名。
-keep class io.github.mangi.eta.ui.navigation.** { *; }

# ── 端侧设备动作模型 ─────────────────────────────────────────────────────────
# 工具函数名/注解由 LiteRT-LM 在运行时通过 kotlin-reflect 读取（KClasses.getFunctions），
# 混淆改名或裁剪注解会让模型调用不到工具。
-keep class io.github.mangi.eta.agent.mobileactions.MobileActionsToolSet { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
