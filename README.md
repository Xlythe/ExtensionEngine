ExtensionEngine
===============
A library that allows Android apps to talk directly to each other at the java layer. Useful for embeding views from one app inside another (See SAO Launcher for an example).

Where to Download
-----------------
```groovy
dependencies {
  compile 'com.xlythe:extension-engine:0.0.4'
}
```

Loading Extensions
----------------
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

Make a call to 'Extension.getApps(Context, byte[]...)' for a list of currently installed extensions.
Use 'Extension.getExtension(Context, String)' to load each extension.
```java
for (App app : Extension.getApps(this)) {
  SAOExtension saoExtension = new SAOExtension(Extension.getExtension(this, app));
  View view = saoExtension.getView(this);
}
```


Creating an Extension
----------------
The first step is to signal to the main app that you are an available extension.
To do that, we add a custom action to an activity in our app.
```xml
<activity android:name=".Stub">
    <intent-filter>
        <action android:name="com.xlythe.saolauncher.EXTENSION" />
    </intent-filter>
    <meta-data android:name="extension" android:value="com.xlythe.unitconverter.sao.UnitConverterExtension" />
</activity>
```
The action points to the main app's package name, and the extension metadata points to the Extension file in our app.
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

Warnings
----------------
Currently, extensions do not work with paid apps. On Android 4.2+, paid apps on the Play Store are installed with encryption enabled by default.
Some development effort has been put into building a proper workaround, but is currently roadblocked. Apps with In App Purchases will work fine.
