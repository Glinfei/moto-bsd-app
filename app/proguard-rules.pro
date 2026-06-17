# Nordic BLE / DFU
-keep class no.nordicsemi.android.** { *; }
-dontwarn no.nordicsemi.android.**

# Keep data models (used by reflection in some cases)
-keep class com.motobsd.model.** { *; }
