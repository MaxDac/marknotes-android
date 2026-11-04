# Rules required by applications that shrink :app-shared.

# JNA maps its library interfaces by reflection (core/notes/CaseRename.kt).
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**

# Markwon probes for optional SVG/GIF decoders; note attachments use its built-in bitmap decoder.
-dontwarn com.caverock.androidsvg.SVG
-dontwarn com.caverock.androidsvg.SVGParseException
-dontwarn pl.droidsonroids.gif.GifDrawable

# PDFBox's optional JPEG 2000 decoder is not shipped; PDF text extraction does not decode images.
-dontwarn com.gemalto.jp2.JP2Decoder
