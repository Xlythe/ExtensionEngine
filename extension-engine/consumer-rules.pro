# A bundled adapter is looked up by its owner package and invoked reflectively.
-keep class * implements com.xlythe.engine.extension.Extension$LegacyAdapter { public *; }
