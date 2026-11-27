# Cross-package loading on Android

The test host and extension have distinct UIDs. The host discovers the extension
through the library manifest query, calls its Java entry point, and checks the
text of a custom view inflated from the extension's layout. It also constructs
`ExtensionContextV2` directly and inflates the layout through that context. The host consumes
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
| 14, 17, 19, 21, 22, 23, 24, 25 | Ordinary APK | Loaded the owner directly, including when a host-bundled adapter was available |
| 14, 17, 19 | Forward-locked private code APK | Loaded the adapter bundled in the host |
| 21 (5.0), 22 (5.1) | Forward-locked ASEC APK | Retried the installed public APK with the host's private DEX cache |
| 23 (6.0) | Forward-locked ASEC APK | Loaded the installed extension directly |
| 24 (7.0) | Forward-locked ASEC APK | Loaded the adapter bundled in the host |
| 25 (7.1.1) | Forward-locked ASEC APK | Loaded the installed extension directly |
| 37 (Android 17) | Bundle-generated base APK and French split | Loaded the installed extension and read the translated layout text |

The owner used above declares no adapter. No companion was installed. On APIs
14, 17, 19, and 24, the same protected owner failed to load in a host without
the adapter. On API 24, a differently signed owner with the correct package
name was rejected even with the adapter present. On APIs 17 and 24 an owner
that explicitly declared a separate adapter also worked when its verified
companion was installed. API 17 rejected a companion signed by a different
key and a same-key companion naming the wrong owner.

## Run the fixture

Use a disposable emulator, set `ANDROID_SERIAL` to its serial number, and run
from the repository root with the Android SDK and Gradle 9.6.1 available.

```sh
gradle -p compatibility :assembleDebug :bundleExtensionDebug
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

## Reproduce the 2017 multidex and resource failures

The fixture generator uses D8 to control class placement and checks each DEX's
class definitions with dexdump. The two bundles exercise distinct lookup paths.

| Bundle | Entry class | Custom view |
| --- | --- | --- |
| `secondary-view.aab` | `classes.dex` | `classes2.dex` |
| `secondary-entry.aab` | `classes2.dex` | `classes3.dex` |

Both passed through the default loader and explicit V2 constructor on APIs
14, 17, 19, 21–25, and 37. API 37 used a separately installed French language
split. The forward-locked three-DEX fixture followed the same adapter and
public-APK paths shown in the installation matrix above. On APIs 17 and 24
both entry points also loaded a three-DEX companion for a protected owner.
On API 19 both rejected an ordinary three-DEX owner signed with an unapproved key.

Build the fixture as above, then generate both bundles in a fresh directory.
These commands exercise an ordinary install on the selected disposable emulator.

```sh
out=$(mktemp -d)
python3 compatibility/multidex_fixture.py "$ANDROID_HOME/build-tools/36.0.0" \
    compatibility/build/outputs/bundle/extensionDebug/*.aab "$out"
adb install -r compatibility/build/outputs/apk/host/debug/*.apk
for placement in secondary-view secondary-entry; do
    bundletool build-apks --mode=universal --bundle="$out/$placement.aab" \
        --output="$out/$placement.apks" --ks="$HOME/.android/debug.keystore" \
        --ks-key-alias=androiddebugkey --ks-pass=pass:android --key-pass=pass:android
    unzip -p "$out/$placement.apks" universal.apk > "$out/$placement.apk"
    adb install -r "$out/$placement.apk"
    adb shell am instrument -w com.xlythe.test.host/.LoadTest
done
```

For API 37 use each generated bundle in the French split procedure above.


## Packaging an adapter for an unreadable historical install

The host's UID must be able to execute an implementation of the extension's
methods. If neither the code APK nor a public implementation is available, the
loader reports `InvalidExtensionException`. Signing both apps with the same key
establishes trust but does not grant filesystem access to the other UID.

For a host-bundled fallback, ship the implementation and its resources in
the host or in an Android library the host consumes. Name the class
`<owner application ID>.Extension` and have it implement
`com.xlythe.engine.extension.Extension.LegacyAdapter`. The engine checks the
installed owner, then looks up that fixed class name on the legacy fallback
path. The marker opts the host class in. The library supplies its shrinker rule.
No manifest mapping, runtime registration, or separate install is needed with
this convention. The constructor receives the host context.

A publisher using a different class name can declare it in the host
application manifest, including through an adapter AAR's manifest merger.

```xml
<meta-data android:name="com.example.calendar.extension.LEGACY_ADAPTER"
    android:value="com.example.host.CalendarAdapter" />
```

The application ID before `.LEGACY_ADAPTER` identifies the installed owner.
Mark custom adapters with the same interface to keep their reflective methods
in release builds. The earlier PR spelling is accepted for existing host builds.

An extension author who distributes an adapter independently can ship the
host-facing entry class and its resources in a separately installed, publicly
readable adapter APK. Add the following metadata to the **registered activity**
in the extension, using the adapter's application ID.

```xml
<meta-data android:name="com.xlythe.engine.extension.LEGACY_ADAPTER"
    android:value="com.example.calendar.adapter" />
```

In the adapter's **application** element, name the owning extension.

```xml
<meta-data android:name="com.xlythe.engine.extension.ADAPTER_OWNER"
    android:value="com.example.calendar.extension" />
```

Sign both packages with the same key. Hosts using certificate pins must register
each package separately. Discovery returns the owning extension. Its existing
entry-class metadata (or its default `.Extension` class name) also names the
class in the adapter. The constructor receives the adapter context so its classes
and resources come from the same APK. Keep its public interface compatible with the host. A host-bundled mapping takes
precedence over a separate adapter on legacy installs. The engine completes
selection before invoking a constructor. If the required adapter is missing or
untrusted, loading fails with `InvalidExtensionException`.

## API 37 feature delivery

The `featureOwner` fixture registers an on-demand extension and packages its
entry class, custom view, translations, mdpi/xxhdpi drawables and JNI library in
the feature. The host tests both public loaders each time the package changes.
On the x86_64 mdpi emulator we exercised these installs:

| Installed state | Observed result |
| --- | --- |
| Two real APK signers | The same set in reversed order loaded. A peer with only one signer from the host set was rejected. |
| Signed update with a proof-of-rotation lineage | Android accepted the update. The previous current key no longer matched the host. Registering the new signer pin restored loading. |
| ADB incremental install of a v4-signed three-DEX APK | The host invoked the entry and inflated its view. |
| Standalone three-DEX owner, then replacement by the feature owner's base alone | Standalone code loaded. Loading the absent feature returned `InvalidExtensionException`. |
| Feature plus its ABI and density splits, French requested | The feature inflated the mdpi drawable and used its default language. |
| Added French split to the existing install, then removed it | The host read French, then the default string on the next load. |
| Attempted partial removal of the feature with required split types | Package Manager rejected the session. The previous install still loaded. A full replacement with base alone removed the feature. |
| Complete split set installed with the base supplied last | Code, mdpi resources and the French translation loaded. |
| French split signed by a different key | Package Manager rejected the session. The prior signed installation passed the same probe. |

On API 37 the platform's isolated feature loader had an empty native search
path. With the updated host, the same extension's `System.loadLibrary` call
succeeded. For a verified isolated feature, the engine gives its defining
class loader the native paths for the host process ABI and keeps the
platform parent and split resources. Android loads stored libraries directly
from the installed APK and compressed libraries from the package's extracted
native directory.

The JNI probe calls a second shared library linked via `DT_NEEDED` and
checks its return value. It passed in all four combinations of isolated or
shared feature loading and stored or compressed native libraries. A copy of
the isolated feature signed with a different key was rejected by both public
loaders. After the host registered that package's certificate, JNI passed.

To reproduce, build the feature bundle using NDK 27.2.12479018. Use the
device spec with French enabled from the split procedure above. Run each
variant on a disposable x86_64 mdpi API 37 emulator with the plain fixture host
installed and its serial in `ANDROID_SERIAL`.

```sh
for isolated in true false; do
    out=$(mktemp -d)
    gradle -p compatibility -PisolatedSplits="$isolated" :featureOwner:bundleDebug
    bundletool build-apks --bundle=compatibility/featureOwner/build/outputs/bundle/debug/featureOwner-debug.aab --output="$out/set.apks" --ks="$HOME/.android/debug.keystore" --ks-key-alias=androiddebugkey --ks-pass=pass:android --key-pass=pass:android
    bundletool extract-apks --apks="$out/set.apks" --device-spec="$french_device_spec" --modules=base,feature --output-dir="$out/selected"
    compatibility/check_feature_install.sh "$out/selected"
done
```

To exercise extracted JNI libraries, repeat both builds with
`-PcompressJni=true` and run the same install script.

For unit-test coverage run
`gradle -Pcoverage :extension-engine:createDebugUnitTestCoverageReport`.
The main loading path constructs `ExtensionContextV1`. The published
`ExtensionContextV2` constructor delegates to it and has direct device tests.
`JointClassLoader` is a deprecated JVM bytecode API with no callers in the
engine. Its tests cover defining a class and delegating resource lookup.
The manifest-registered `DexFileProvider` serves older clients independently
of the loading path. Its public pipe callback remains for API compatibility.

The report in `extension-engine/build/reports/coverage/test/debug/` measures
JVM tests only. The installed-APK probes above exercise the platform class
loaders and Package Manager on devices.

## Camera operations

With a disposable English-language emulator selected in `ANDROID_SERIAL`, build both debug APKs and run the camera smoke test. The test clears data for those two fixture packages.

```sh
gradle -p compatibility :assemblePreviewHostDebug :assemblePreviewExtensionDebug
adb install -r compatibility/build/outputs/apk/previewHost/debug/*.apk
adb install -r compatibility/build/outputs/apk/previewExtension/debug/*.apk
python3 compatibility/check_preview.py
```

Pass `--expect-barcode 'ExtensionEngine test scan'` when the emulated camera points at a QR code with that text.

## APK replacement

Build two revisions and exercise both update directions on a disposable emulator. Each revision contains different extension bytecode and a different resource value. The camera probe requires an emulated camera.

```sh
out=$(mktemp -d)
for revision in 1 2; do
    mkdir "$out/$revision"
    gradle -p compatibility -PfixtureRevision="$revision" assembleHostDebug assembleExtensionDebug assemblePreviewHostDebug assemblePreviewExtensionDebug
    for role in host extension previewHost previewExtension; do
        cp compatibility/build/outputs/apk/"$role"/debug/*.apk "$out/$revision/$role.apk"
    done
done
python3 compatibility/check_updates.py "$out/1" "$out/2"
```

Use `--embedded-only` for an emulator without a camera.
