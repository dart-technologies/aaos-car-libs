# Host A (templates) release keeps. Manifest-referenced entry points are kept by AGP and
# car-app ships its own consumer rules; these pin the boundary the templates host crosses by
# name, so a rename breaks the build instead of the bind.
-keep class art.dart.a2ui.carapp.A2uiCarAppService { *; }
-keep class art.dart.a2ui.carapp.A2uiSession { *; }
-keep class * extends androidx.car.app.Screen { *; }
