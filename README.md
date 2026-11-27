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

A barcode scanner can use its own camera permission and return a string to a host that never declares `CAMERA`. In the **host** (a `ComponentActivity` or `AppCompatActivity`), launch the trusted scanner when the user taps Scan. The two apps agree on the operation name and result extra.

```java
private final ActivityResultLauncher<Intent> scan = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (result.getResultCode() == RESULT_OK && data != null) {
                barcodeLabel.setText(data.getStringExtra("barcode"));
            }
        });

void scanBarcode() {
    Intent request = ExtensionOperations.approvalIntent(this, "com.example.scanner");
    scan.launch(request.putExtra("operation", "scan"));
}
```

In the **extension APK**, declare `android.permission.CAMERA` and advertise the approval activity on the existing registration activity with `<meta-data android:name="com.xlythe.engine.extension.OPERATION_APPROVAL" android:value=".ScanApproval" />`. Export the approval activity and make the expected host visible to the extension's package manager. Separately signed peers register each other's package and certificate before use. [Complete example manifest](compatibility/src/previewExtension/AndroidManifest.xml)

```java
public final class ScanApproval extends OperationPermissionActivity {
    @Override protected String hostPackage() { return "com.example.host"; }
    @Override protected String operation() { return "barcode.scan.v1"; }
    @Override protected String permission() { return Manifest.permission.CAMERA; }

    @Override protected void onRequestOperation() {
        requestOperationPermission();  // The user selected Scan in the host.
    }

    @Override protected void onAuthorized() {
        startActivity(new Intent(this, ScannerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT));
        finish();
    }
}
```

On API 23+ a missing grant triggers Android’s runtime permission request for the extension. Android decides whether to show a dialog. Denial returns a canceled result. An existing grant proceeds directly to `onAuthorized()`. The private scanner uses the camera in its own process and calls `setResult(RESULT_OK, new Intent().putExtra("barcode", text))` and `finish()`. An extension can also render its own explanation in `onRequestOperation()` and call `requestOperationPermission()` from a button. The library supplies no dialog or layout. [Runnable scanner](compatibility/src/previewExtension/java/com/xlythe/test/preview/ScannerActivity.java)

**For a live preview inside the host**, the extension first asks whether the host may receive camera images. The default `onAuthorized()` returns a single-use approval to the host. Once the host activity is visible again, it binds to the registered service and passes the approval and its preview surface through typed AIDL.

```java
// Host, after RESULT_OK
IBinder approval = OperationApproval.token(resultIntent);
boolean bound = ExtensionOperations.bind(this, extensionPackage, connection,
        /* shareWhileInUseCapabilities= */ true);
// In connection.onServiceConnected(), with the host's surface and callback ready:
IPreviewV1.Stub.asInterface(binder).start(approval, surface, events);

// Extension, at the start of the incoming IPreviewV1.start() call
OperationApproval.consume(this, HOST_PACKAGE, "camera.preview.v1", approval);
// Open Camera2 here in the extension process and direct its output to the supplied surface.
```

Stop the session and unbind the successful connection when the host leaves the preview. The sample handles service death, permission loss and surface teardown. [Host lifecycle](compatibility/src/previewHost/java/com/xlythe/test/preview/PreviewActivity.java) · [Camera service](compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewService.java)
