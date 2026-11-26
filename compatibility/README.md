# Cross-package loading on Android

The test host and extension have distinct UIDs. The host discovers the extension
through the library manifest query, calls its Java entry point, and checks the
text of a custom view inflated from the extension's layout. The host consumes
the library through a Gradle composite build and selects AppCompat 1.6.1 to
support API 14. The test reports the selected class loader, code package,
`sourceDir`, and `publicSourceDir`.

## Installation history

- Android 4.0 already supported forward locking. On the API 14 emulator,
  `pm install -l` placed the code under `/data/app-private` and a public resource
  ZIP under `/data/app`. Normal APK installs were readable by the host.
- In June 2012 Google announced device-specific encryption for paid Play apps
  with Android 4.1 (API 16). The project's March 2014 README specifically
  recorded a paid-extension failure on Android 4.2+ (API 17). We reproduced
  the loading failure on API 17 with the platform installer: it put `pkg.apk`
  and `res.zip` in an ASEC container, and the host could not resolve the entry
  class. [Jelly Bean announcement](https://android-developers.googleblog.com/2012/06/introducing-android-41-jelly-bean.html)
  · [2014 README](https://github.com/Xlythe/ExtensionEngine/blob/0deca57/README.md)
- In April 2017 the experimental V2 loader tried having the extension serve
  its APK through a provider. The author identified resource loading and
  multidex as blockers and left V1 selected. [V2 commit](https://github.com/Xlythe/ExtensionEngine/commit/2997cd52933a49e886619b9d9ffc72a4da11b088)
- ASEC installation still existed on Android 7.1 (API 25). Our emulator
  accepted the forward-lock flag, placed the extension in ASEC, and reported
  the same readable base APK for code and resources. Android 8.0 (API 26)
  explicitly rejected new forward-locked ASEC installs. An API-level check
  alone would miss the distinction between ordinary and protected installs on
  the same device. On API 21 the paths also matched, but ART failed to open
  the optimized DEX on a forward-locked install. On APIs 21 and 22, the host resolved the class from the same public APK
  using a host-private optimization directory. API 23 loaded directly. On
  API 24, SELinux denied the host directory search on the ASEC mount and
  Android threw `Resources.NotFoundException` during `createPackageContext`.
  The host-bundled mapping handled that earlier failure.
  [DexClassLoader and its private cache](https://developer.android.com/reference/dalvik/system/DexClassLoader) · [Android 8.0 installer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-8.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerService.java#L629-L633)
- On Android 17 (API 37), we installed the signed base and French language
  split generated from an app bundle over ADB. A host targeting 37 loaded the
  entry class from the extension and read its French layout text. The exercise
  covers the installed code and resources. A Play transaction was outside
  this test. [App bundle format](https://developer.android.com/guide/app-bundle/app-bundle-format)

## Verified on emulators

| API | Installation tested | Result |
| --- | --- | --- |
| 14, 17, 19, 21, 22, 23, 24, 25 | Ordinary APK | Loaded the owner directly, including when a host-bundled mapping was available |
| 14, 17, 19 | Forward-locked private code APK | Loaded the adapter bundled in the host |
| 21 (5.0), 22 (5.1) | Forward-locked ASEC APK | Retried the installed public APK with the host's private DEX cache |
| 23 (6.0) | Forward-locked ASEC APK | Loaded the installed extension directly |
| 24 (7.0) | Forward-locked ASEC APK | Loaded the adapter bundled in the host |
| 25 (7.1.1) | Forward-locked ASEC APK | Loaded the installed extension directly |
| 37 (Android 17) | Bundle-generated base APK and French split | Loaded the installed extension and read the translated layout text |

The owner used above declares no adapter. No companion was installed. On APIs
14, 17, 19, and 24, the same protected owner failed to load in a host without
the mapping. On API 24, a differently signed owner with the correct package
name was rejected even with the mapping present. On APIs 17 and 24 an owner
that explicitly declared a separate adapter also worked when its verified
companion was installed. API 17 rejected a companion signed by a different
key and a same-key companion naming the wrong owner.

## Run the fixture

Use a disposable emulator, set `ANDROID_SERIAL` to its serial number, and run
from the repository root with the Android SDK and Gradle 9.6.1 available.

```sh
gradle -p compatibility assembleDebug bundleExtensionDebug
apk=compatibility/build/outputs/apk
adb install -r "$apk"/host/debug/*.apk
adb install -r "$apk"/extension/debug/*.apk
adb shell am instrument -w com.xlythe.test.host/.LoadTest
```

A successful run prints `result=PASS` and `INSTRUMENTATION_CODE: -1`. To
test a forward-locked installation on APIs 14–25, replace the fixture owner on
the disposable emulator. The bundled host has the same application ID and
signer as the original host. It includes a host-only layout with distinct text.

```sh
adb install -r "$apk"/bundledHost/debug/*.apk
adb uninstall com.xlythe.test.extension
adb push "$apk"/extension/debug/*.apk /data/local/tmp/extension-test.apk
adb shell pm install -l /data/local/tmp/extension-test.apk
adb shell dumpsys package com.xlythe.test.extension
# APIs 14, 17, 19, 24 in the matrix
adb shell am instrument -w -e codePackage com.xlythe.test.host com.xlythe.test.host/.LoadTest
# APIs 21, 22, 23, 25 in the matrix
adb shell am instrument -w com.xlythe.test.host/.LoadTest
```

The report identifies the installed owner on APIs 21–23 and 25. A
`PublicApkContext$Loader` identifies the private cache retry.
`dalvik.system.PathClassLoader` identifies a direct platform load.
The test also checks that two simultaneous instances use the same class loader.

For the separate-companion case, install the plain host, reinstall the
`extensionWithAdapter` flavor with `pm install -l`, install the ordinary
`adapter` APK, and pass `-e codePackage com.xlythe.test.adapter` on API 17 or 24.

To test API 37 split delivery, select the French split even on an English
emulator. The host requests a French configuration for this test. The expected
text exists only in the French resources.

```sh
out=$(mktemp -d)
bundletool build-apks --bundle=compatibility/build/outputs/bundle/extensionDebug/extension-compatibility-extension-debug.aab --output="$out/set.apks" --ks="$HOME/.android/debug.keystore" --ks-key-alias=androiddebugkey --ks-pass=pass:android --key-pass=pass:android
bundletool get-device-spec --device-id="$ANDROID_SERIAL" --output="$out/device.json"
jq '.supportedLocales += ["fr-FR"]' "$out/device.json" > "$out/french.json"
bundletool extract-apks --apks="$out/set.apks" --device-spec="$out/french.json" --output-dir="$out/selected"
adb install-multiple -r "$out"/selected/*.apk
adb shell pm path com.xlythe.test.extension
adb shell am instrument -w -e language fr com.xlythe.test.host/.LoadTest
```
