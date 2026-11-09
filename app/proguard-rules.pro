# Keep Room database & entities
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep kotlinx.serialization models for JSON backup/restore
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.baonplan.finance.data.backup.**$$serializer { *; }
-keepclassmembers class com.baonplan.finance.data.backup.** {
    *** Companion;
}
-keepclasseswithmembers class com.baonplan.finance.data.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Navigation 3 @Serializable NavKeys and @Serializable domain enums
-keep,includedescriptorclasses class com.baonplan.finance.ui.navigation.**$$serializer { *; }
-keepclassmembers class com.baonplan.finance.ui.navigation.** {
    *** Companion;
}
-keepclasseswithmembers class com.baonplan.finance.ui.navigation.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.baonplan.finance.ui.navigation.** { *; }
-keep class com.baonplan.finance.domain.model.** { *; }
