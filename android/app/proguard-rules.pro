# QR skaner (zxing-android-embedded): CaptureActivity manifestdən çağırılır,
# kitabxana daxilində refleksiya da var — R8 heç nəyi silməsin.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
