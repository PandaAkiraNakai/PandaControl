# Mantener clases anotadas con kotlinx.serialization
-keep,includedescriptorclasses class io.github.pandaakira.apppanda.**$$serializer { *; }
-keepclassmembers class io.github.pandaakira.apppanda.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.pandaakira.apppanda.** {
    kotlinx.serialization.KSerializer serializer(...);
}
