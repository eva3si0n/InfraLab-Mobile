-keep class com.eva3si0n.infralab.data.** { *; }
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**

# Tink (тянется за EncryptedSharedPreferences) ссылается на аннотации errorprone,
# которых нет в рантайме — R8 из-за них падал, и release-вариант не собирался вовсе.
# Аннотации нужны только на этапе компиляции, глушим предупреждения.
-dontwarn com.google.errorprone.annotations.**
