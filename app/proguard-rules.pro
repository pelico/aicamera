# ProGuard / R8
# 目前 release 未开启混淆（isMinifyEnabled = false），如需开启请保留下面两条规则，
# 否则 ONNX Runtime 与 TFLite 的 JNI 入口会被裁掉。

-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
