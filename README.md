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

Legacy paid extensions
----------------
Some older installs put executable code in a private APK and expose only a
resource archive to other UIDs. The minimum supported API is 14.

On APIs 14–20 the engine checks a verified, readable APK for secondary DEX
files before loading its entry class. If present, the engine loads the primary
and secondary DEX together so extension methods and custom views can reference
classes in any of them. The host uses its private cache for the secondary files.
The public V2 context uses the same selected package for classes, resources,
and layout inflation as `Extension.getExtension()`.

For API 14 hosts using Gradle, select an AppCompat release that still supports
API 14, such as 1.6.1, within the library’s allowed range.

On APIs 21–25 the engine can retry a missing class from the installed public
APK using a host-private DEX cache. This needs no extension change or user
action. On some devices Android denies the host access to the entire package.

For those installs, a host can bundle a compatible entry class and its resources
in the host APK. Register the class in the **host application's** manifest,
using the installed extension's exact package name as the suffix. For example:

```xml
<meta-data android:name="com.xlythe.engine.extension.LEGACY_ADAPTER.com.example.calendar.extension"
    android:value="com.example.host.CalendarAdapter" />
```

The bundled class implements the extension methods the host uses. Its constructor
receives a host package context and its layouts must be available in the host.
Preserve its name, constructor, and reflective methods in release builds (for
example, annotate the class with `@Keep`). The engine selects it only below
API 26, after verifying the registered owner and failing to open its package
context or resolve its entry point. Users get this support in a host update.
An already-installed extension needs no metadata changes or companion install.
The host must have an implementation for each protected extension it supports.

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

See the [compatibility tests and installation history](compatibility/README.md).
