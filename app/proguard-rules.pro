# R8/ProGuard 保留规则（FindIt）
# 说明：release 已开启 isMinifyEnabled=true（AGP 8 默认 R8 full mode）。

# pinyin4j：PinyinHelper 通过 getResourceAsStream 按名称加载拼音库（反射式加载），整包保留
-keep class net.sourceforge.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**

# NanoHTTPD：内置 HTTP 服务，serve() 等回调按类名/方法分发，整包保留
-keep class fi.iki.elonen.** { *; }

# zxing core + zxing-android-embedded（二维码生成/扫码）
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# 应用自身类不额外保留：正常入口（Activity/Service）由 Android 规则自动保留，
# HostRuntime/SyncCrypto 等由代码直接引用，R8 不会误删。
