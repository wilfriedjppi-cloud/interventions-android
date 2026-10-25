# Interventions : code Java rendu illisible (R8), sauf ce que la page appelle
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface
-dontwarn **
