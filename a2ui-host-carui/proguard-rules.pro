# Host B (native activities) release keeps. Manifest-referenced entries are kept by AGP;
# pinning them anyway turns a manifest-code rename skew into a build failure, not a dead launch.
-keep class art.dart.a2ui.carui.A2uiCarUiActivity { *; }
-keep class art.dart.a2ui.carui.A2uiPassengerActivity { *; }
