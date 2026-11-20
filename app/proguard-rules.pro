# Shizuku instantiates the display service by class name in its own process, calling the
# (Context) constructor; the AIDL stub and proxy cross that process boundary.
-keep class io.github.npfp222.hzunlock.DisplayService { public <init>(android.content.Context); *; }
-keep class io.github.npfp222.hzunlock.IDisplayService { *; }
-keep class io.github.npfp222.hzunlock.IDisplayService$* { *; }

# The service process calls hidden framework classes by reflection. Those names live in the
# framework, not this APK, but keep our callers' records and helpers readable in stack traces.
-keepattributes SourceFile,LineNumberTable
-keep class io.github.npfp222.hzunlock.DisplayControl { *; }
-keep class io.github.npfp222.hzunlock.DisplayControl$* { *; }
-keep class io.github.npfp222.hzunlock.RefreshSession { *; }
-keep class io.github.npfp222.hzunlock.SurfaceFlingerCodes { *; }
-keep class io.github.npfp222.hzunlock.SurfaceFlingerCodes$* { *; }
