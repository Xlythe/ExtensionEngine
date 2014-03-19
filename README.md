ExtensionEngine
===============
A library that allows Android apps to talk directly to each other at the java layer. Useful for embeding views from one app inside another (See SAO Launcher for an example). Also, it's expected that the extension interface will be forked and changed to fit your needs.


The extension app will include Extension.java in their default package, typically implmenting ExtensionInterface. It will also include an Intent Filter pointing to the app it wishes to work with.

The extended app will call Extension.getApps for a list of all installed extensions, and Extension.getExtension for each extension.


There are some very important notes about this library
1) Paid apps on Google Play cannot be extensions (or they will break on Android 4.2+). In App Purchases work fine.
2) Avoid using the same class path for the extension and the main app. Especially for custom views, if the xml hasn't been inflated yet it may possibly break. This means, if using the Theme Engine with this, the extension should copy the theme library into a new package instead of including it like a typical library.