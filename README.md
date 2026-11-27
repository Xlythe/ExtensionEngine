ExtensionEngine
===============
A library that allows Android apps to talk directly to each other at the java layer. Useful for embedding views from one app inside another (See SAO Launcher for an example).

Where to Download
-----------------
```groovy
dependencies {
  compile 'com.xlythe:extension-engine:0.0.5'
}
```

Loading Extensions
----------------
The default discovery action is the host's application ID followed by `.EXTENSION`.
For example, the host `com.xlythe.saolauncher` queries for
`com.xlythe.saolauncher.EXTENSION`. In this checkout the library adds that query
to the host's manifest automatically, using the host variant's final application ID.
Only hosts using 0.0.5 or a custom discovery action need to add a matching
`<queries><intent>` entry themselves when targeting Android 11 or later.

Define your extension
```java
public class SAOExtension extends Extension {
    public SAOExtension(Extension extension) {
        super(extension);
    }

    public View getView(Context context) {
        return (View) invoke("getView", new ReflectionPair<>(Context.class, context));
    }
}

```

Call `Extension.getApps(context)` to discover trusted extensions. Pass the resulting
`App` to `Extension.getExtension(context, app)` to load its registered class.
```java
for (App app : Extension.getApps(this)) {
  SAOExtension saoExtension = new SAOExtension(Extension.getExtension(this, app));
  View view = saoExtension.getView(this);
}
```


### Trusting an extension

The following trust policy and registration API are available in this checkout.

The engine loads extension code into the host process with the host's privileges.
Discovery and loading both check the installed package and its current APK signing
certificates. By default, the complete signer set must match the host's, including
in debug builds. Direct loads also require an activity registered for the host's
extension action.

For a separately signed extension, register its exact application ID and SHA-256
app signing certificate fingerprint in the host before discovery or loading, for
example in `Application.onCreate()`.

```java
Security.addAllowedSigningCertificate("com.example.calendar.extension", CALENDAR_SIGNING_SHA256);
```

Use the fingerprint of the certificate on the distributed APK. Play App Signing
shows this under **App signing key certificate**. Pins accept plain hex or
colon-separated hex pairs, apply to single-signer APKs, and last for the current
process. Android still applies the manifest visibility rules above when a pin is registered.
Register both fingerprints during a planned key rotation. The optional
raw certificate arguments to `getApps` further filter the trusted results.

This library compiles against Android API 37 and targets API 37 for its test APK
and lint checks. Set `targetSdk 37` in the consuming app to opt into Android 17
runtime behavior.

Creating an Extension
----------------
In the **extension APK's manifest**, register an activity for the **host's**
discovery action. Here the extension's application ID is
`com.xlythe.unitconverter.sao` and the host's is `com.xlythe.saolauncher`.
```xml
<activity android:name=".Stub" android:exported="true">
    <intent-filter>
        <action android:name="com.xlythe.saolauncher.EXTENSION" />
    </intent-filter>
    <meta-data android:name="extension" android:value="com.xlythe.unitconverter.sao.UnitConverterExtension" />
</activity>
```
Use the host's literal action here, or supply it through a custom Gradle manifest
placeholder if it varies by build. In this manifest, `${applicationId}` expands
to the extension's own ID. The metadata names the implementation in the extension APK.
The next step is, obviously, to create that extension file.
```java
package com.xlythe.unitconverter.sao;

public class UnitConverterExtension {}
```
The main app should have provided an interface to implement (or, if not, a list of methods that are expected to exist).
For SAO, we're expected to have a method called 'View getView(Context)'. Remember to load Views using the ExtensionInflater.inflate method.
```java
public View getView(Context context) {
  return ExtensionInflater.inflate(context, R.layout.sao_extension, frame);
}
```
At this point, your extension is done. It's up to the main app to list out what methods it requires from the extension
and it's up to the extension to fully implement those methods.

Package loading
---------------
Both public entry points handle readable multidex APKs, installed feature code,
and configuration splits inside the library. Hosts call the same discovery and
loading methods across supported Android versions (API 14 and up).

Some historical paid installs keep the executable APK private to the extension's
UID. If the publisher includes a host-side implementation, the engine can select
it automatically for the verified owner. The ordinary install and split paths
require no adapter configuration. [Legacy adapter packaging and device tests](compatibility/README.md)

### Extension-owned permissions

A host can use a trusted extension for an operation that requires a permission the host lacks. For example, a scanner could use the extension's camera and return a barcode, or a voice recorder could use its microphone and return a recording. The host can check its own permission with Android’s usual APIs and explicitly choose a local implementation or the extension operation. The Android permission belongs to the extension, which implements the operation and decides what to share.

**Check and request a permission.** In a host `ComponentActivity`, register the launcher once. Pass the Android permission name when the user selects the feature.

```java
private final ActivityResultLauncher<String> requestPermission = registerForActivityResult(
        new ExtensionPermissions.RequestPermission(EXTENSION_PACKAGE), granted -> {
            if (granted) startExtensionScan();
        });

void onScanClick() {
    if (ExtensionPermissions.checkPermission(this, EXTENSION_PACKAGE, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
        startExtensionScan();
    } else {
        requestPermission.launch(Manifest.permission.CAMERA);
    }
}
```

The extension declares the permission it uses and registers its existing activity for the host's `.EXTENSION` action. The library manifest adds the permission-request activity automatically. The engine checks registration and signing identity on both sides. Android handles the runtime prompt on API 23+. On API 18–22 the result reflects the install-time grant. Special access that Android manages through Settings needs its own platform flow. Separately signed apps register each other's signing certificates.

**Return a result.** A scanner could launch its registered extension activity with `ExtensionOperations.approvalIntent(this, EXTENSION_PACKAGE)` using Android’s normal activity-for-result API. In the sample, an `"operation"` extra selects `"scan"`. The extension rechecks its permission and returns the decoded text with `setResult(RESULT_OK, new Intent().putExtra("barcode", text))`. The host reads the result in its activity callback. [Host example](compatibility/src/previewHost/java/com/xlythe/test/preview/PreviewActivity.java) · [Scanner](compatibility/src/previewExtension/java/com/xlythe/test/preview/ScannerActivity.java)

**Keep an operation running while the host displays it.** A live preview shares pixels with the host. The sample extension uses its own approval UI to ask the user about that sharing, even with camera permission already granted. After the user accepts and the host is visible, open the approved operation and supply the preview surface.

```java
Bundle inputs = new Bundle();
inputs.putParcelable("surface", previewSurface);
session = OperationSession.open(this, EXTENSION_PACKAGE, "camera.preview.v1",
        approvalResult, inputs, /* shareWhileInUseCapabilities= */ true, this::onPreviewEnded);
```

Call `session.close()` when the host pauses or loses its surface. In the extension, implement `OperationService.onOpenSession(operation, inputs, session)` and `onCloseSession(session)`. Open Camera2 against the supplied surface and close the camera when the session ends. Recheck the extension's current grant before starting work. The library handles binding, foreground importance, single-use approval, and peer death. Advertise the service with the host's `.EXTENSION_SERVICE` action. The matching registration activity handles operation approval by default. [Extension approval UI](compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewApproval.java) · [Camera service](compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewService.java) · [Example manifest](compatibility/src/previewExtension/AndroidManifest.xml)

### APK updates

A loaded extension represents one installation of its owner and adapter. When either package changes, discard its views and objects and call `Extension.getExtension(...)` again. Use `isCurrent()` to check a retained handle. Calling `invoke` on a superseded handle throws `IllegalStateException`. After Android restarts the host, initialize the extension as on a cold launch.

Replacing either APK ends a live camera session. The host should release its preview, bind again and ask the user to start a new session. Pending approvals expire when the issuing process dies or the requesting host APK changes.
