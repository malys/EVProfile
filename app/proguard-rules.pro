# Keep all reflection targets intact (SAIC SDK, android.car.Car, ServiceManager)
-keep class android.car.** { *; }
-keep class android.os.ServiceManager { *; }
-keep class com.saicmotor.** { *; }
-keep class com.evsuite.hardware.model.** { *; }
-keep class com.evsuite.hardware.EVHardware { *; }

# Reflected by Class.forName in EVHardware/FirmwareInfo — same treatment as
# ServiceManager, which was already listed.
-keep class android.os.SystemProperties { *; }

# External control IPC contract. The AIDL Stub/Proxy is resolved by name on the client
# side: renaming these classes would break the bind instead of failing at compile time.
-keep interface com.evsuite.profile.api.IProfileControl { *; }
-keep class com.evsuite.profile.api.IProfileControl$* { *; }

# Gson (profiles and backups). Without Signature, the generic type of
# TypeToken<List<DrivingProfile>> is erased and deserialization returns a list of
# LinkedTreeMap instances: profiles would silently disappear on the first launch of a
# minified release.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Serialized class fields are read through reflection; do not rename them.
-keepclassmembers class com.evsuite.hardware.model.** {
    <fields>;
    <init>(...);
}

# Keep stack traces useful in CrashLogger reports. Otherwise obfuscated lines make the
# report unusable without the mapping file.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
