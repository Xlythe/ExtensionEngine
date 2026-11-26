# Cross-package loading on Android

The test host and extension have distinct UIDs. The host discovers the extension
through the library manifest query, calls its Java entry point, and checks the
text inflated from the extension's layout. The host consumes the library through a Gradle composite build and selects
AppCompat 1.6.1 so its dependency graph supports API 14. The test reports the code package,
`sourceDir`, and `publicSourceDir` so the installation layout is observable.

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
  the optimized DEX on a forward-locked install. This is why the engine tests
  whether the class resolves before selecting an explicitly declared adapter. [Android 8.0 installer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-8.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerService.java#L629-L633)
- On Android 17 (API 37), we installed the signed base and French language
  split generated from an app bundle over ADB. A host targeting 37 loaded the
  entry class from the extension and read its French layout text. The exercise
  covers the installed code and resources. A Play transaction was outside
  this test. [App bundle format](https://developer.android.com/guide/app-bundle/app-bundle-format)

## Verified on emulators

| API | Installation tested | Result |
| --- | --- | --- |
| 14 (4.0.2), 17 (4.2.2), 19 (4.4), 21 (5.0) | Ordinary APK | Loaded extension class and layout directly |
| 14, 17, 19, 21 | Forward-locked extension | Direct load failed. Installing the declared adapter produced `result=PASS` |
| 25 (7.1.1) | Ordinary and forward-locked extension | Both loaded directly |
| 37 (Android 17) | Bundle-generated base APK and French split installed together | Loaded directly and read the translated layout text |

On API 17 the host also rejected an adapter with a different signer and a
same-signer adapter naming the wrong owner. The public adapter is an opt-in
packaging option. Previously shipped protected extensions without a readable
entry point need an author update to use it.

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

A successful run prints `result=PASS` and `INSTRUMENTATION_CODE: -1`. To test
the protected layout on API 14 or 17, replace only the test extension on that
emulator. With no adapter installed the load should fail. Install the adapter
as an ordinary APK and confirm the same call now succeeds.

```sh
adb uninstall com.xlythe.test.extension
adb push "$apk"/extension/debug/*.apk /data/local/tmp/extension-test.apk
adb shell pm install -l /data/local/tmp/extension-test.apk
adb shell am instrument -w com.xlythe.test.host/.LoadTest
adb install -r "$apk"/adapter/debug/*.apk
adb shell am instrument -w -e codePackage com.xlythe.test.adapter com.xlythe.test.host/.LoadTest
```

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
