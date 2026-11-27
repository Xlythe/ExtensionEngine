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

### Making an Android call in the extension

A method you call through `Extension.invoke()` runs **inside the host process**, even though its bytecode came from the extension APK. Android checks the host's permissions there. To use a permission held by the extension, have the host explicitly launch an Activity in the extension or open a session with its service. Code inside that component calls the regular Android API with the extension's identity.

The operations API in this checkout supports Android API 18 and later. Define one operation per feature you want to offer. An operation can use as many Android methods as it needs. If the host also has a local implementation, it can check its own permission with `ContextCompat.checkSelfPermission` and choose which path to call. ExtensionEngine only sends the operations you explicitly start.

#### Vibrate once

In the **host**, launch the registered extension Activity for a result when the user presses the button. The string is an operation name agreed on by the two apps. The host sample declares neither `VIBRATE` nor `CAMERA`. [Host button and result handler](compatibility/src/previewHost/java/com/xlythe/test/preview/PreviewActivity.java)

```java
Intent pulse = ExtensionOperations.approvalIntent(this, extensionPackage);
pulse.putExtra("operation", "haptics.pulse.v1");
startActivityForResult(pulse, REQUEST_PULSE);
```

In the **extension**, declare `<uses-permission android:name="android.permission.VIBRATE" />` and register an `OperationPermissionActivity` for the host's `.EXTENSION` action. Here is a complete handler for an extension offering just that operation. Android grants the normal `VIBRATE` permission at install time. The sample that handles both vibration and camera also selects the default vibrator through `VibratorManager` on API 31+. [Extension manifest](compatibility/src/previewExtension/AndroidManifest.xml) · [Multi-operation handler](compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewApproval.java)

```java
public class PulseActivity extends OperationPermissionActivity {
    @Override protected String operation() { return "haptics.pulse.v1"; }
    @Override protected String permission() { return Manifest.permission.VIBRATE; }

    @Override protected void onRequestOperation() {
        if (operation().equals(getIntent().getStringExtra("operation"))) requestOperationPermission();
        else finish();
    }

    @Override protected void onAuthorized() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(100);
            }
            setResult(RESULT_OK);
        }
        finish();
    }
}
```

The host receives `RESULT_OK` when the extension submits the pulse. A device without a vibrator returns `RESULT_CANCELED`. A one-shot operation can finish inside this Activity.

#### Display a live camera preview in the host

The Camera2 sample runs on API 21 and later.

1. **Ask to share the camera.** The host launches the same registered extension Activity with `"camera.preview.v1"`. The extension declares `CAMERA`, explains that the host will receive live images, and calls `requestOperationPermission()` when the user accepts. This opens Android's runtime prompt if needed. The default `onAuthorized()` returns an approval for one session. [Extension approval](compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewApproval.java)
2. **Send the destination for frames.** When the host has the successful Activity result, is resumed, and its `TextureView` is available, it creates a `Surface` from that view. The code below sends the operation name, approval and surface over Binder to the extension's registered `OperationService`. [Host connection and surface lifecycle](compatibility/src/previewHost/java/com/xlythe/test/preview/PreviewActivity.java#L104) · [Service registration](compatibility/src/previewExtension/AndroidManifest.xml)

   ```java
   // Host Activity, after approval and surface availability
   Bundle inputs = new Bundle();
   inputs.putParcelable("surface", surface);
   connection = OperationSession.open(this, extensionPackage, "camera.preview.v1",
           approvalResult, inputs, /* shareWhileInUseCapabilities= */ true, this::stop);
   ```

3. **Open and drive the camera in the extension.** Binder delivers the request to `PreviewService.onOpenSession`. On its camera worker thread the service checks its own `CAMERA` grant, gets `CameraManager` from its service context, and calls `openCamera`. In `onOpened` it calls `createCaptureSession` with the host's surface. In `onConfigured` it builds a `TEMPLATE_PREVIEW` request, calls `addTarget(surface)`, then `setRepeatingRequest`. Android writes frames to that surface and the host's `TextureView` displays them. The `CameraDevice` and `CameraCaptureSession` stay in the extension process. [Opening the camera](compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewService.java#L77) · [Configuring the capture request](compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewService.java#L113)

   ```java
   // Extension service, inside CameraCaptureSession.StateCallback.onConfigured
   CaptureRequest.Builder preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
   preview.addTarget(surface);
   capture.setRepeatingRequest(preview.build(), null, worker);
   ```

4. **Stop sharing.** Close the host's `OperationSession` when the Activity pauses or loses its surface. The extension's close callback releases its capture session, camera and surface handle. [Host cleanup](compatibility/src/previewHost/java/com/xlythe/test/preview/PreviewActivity.java#L124) · [Extension cleanup](compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewService.java#L130)

If you only need a barcode, the extension can run the scanner in its own Activity and return the decoded string in the result. To ask for the extension's runtime grant before starting the scanner, register a launcher in the host `ComponentActivity` as shown below. [Host scan button](compatibility/src/previewHost/java/com/xlythe/test/preview/PreviewActivity.java) · [Result-only scanner](compatibility/src/previewExtension/java/com/xlythe/test/preview/ScannerActivity.java)

```java
private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
        new ExtensionPermissions.RequestPermission(PROVIDER), granted -> {
            if (granted) launchOperation("scan", SCAN);
        });

void onScanClick() {
    if (ExtensionPermissions.checkPermission(this, PROVIDER, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
        launchOperation("scan", SCAN);
    } else {
        cameraPermission.launch(Manifest.permission.CAMERA);
    }
}
```

### APK updates

A loaded extension represents one installation of its owner and adapter. When either package changes, discard its views and objects and call `Extension.getExtension(...)` again. Use `isCurrent()` to check a retained handle. Calling `invoke` on a superseded handle throws `IllegalStateException`. After Android restarts the host, initialize the extension as on a cold launch.

Replacing either APK ends a live camera session. The host should release its preview, bind again and ask the user to start a new session. Pending approvals expire when the issuing process dies or the requesting host APK changes.
