# car-ui-lib plugin API prebuilt

`car-ui-lib-plugin-apis-1.5.0.aar` is the exact artifact used by the Gradle build:
`com.android.car.ui:car-ui-lib-plugin-apis:1.5.0`.

- Source: `https://dl.google.com/dl/android/maven2/com/android/car/ui/car-ui-lib-plugin-apis/1.5.0/car-ui-lib-plugin-apis-1.5.0.aar`
- SHA-256: `7a57d30959cc28fc82a87364fe7a81071f30a92d20ec5f271853bb3a8ff1a562`
- License: Apache License 2.0, as declared by the matching Google Maven POM
- Contents: manifest, empty `R.txt`, `classes.jar`, and upstream third-party license notices

The AAR is imported by Soong as `car-ui-lib-oem-apis`. An OEM/AOSP owner must build that module,
`car-ui-lib`, and `car-ui-lib-plugin` before this branch is described as merge-ready.
