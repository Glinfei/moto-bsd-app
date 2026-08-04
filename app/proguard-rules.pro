# MotoBSD ProGuard/R8 rules
# Keep Nordic BLE/DFU library classes that use reflection
-keep class no.nordicsemi.android.ble.** { *; }
-keep class no.nordicsemi.android.dfu.** { *; }

# Keep GATT callback bridge methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep data classes used by StateFlow/Compose
-keep class com.motobsd.model.** { *; }
