WATERMARK REMOVER - ANDROID (on-device AI, LaMa via ONNX Runtime)

BUILD THE APK (pick one)

A) No Android Studio - GitHub builds it for you
   1. Create a new GitHub repo, upload everything in this folder
      (keep the hidden .github folder!).
   2. Open the repo's Actions tab -> "Build APK" runs automatically
      (or press Run workflow).
   3. When it finishes, open the run -> Artifacts -> WatermarkRemover-apk
      -> download, unzip, you get app-debug.apk.

B) Android Studio
   File > Open this folder, wait for Gradle sync,
   Build > Build APK(s). APK: app/build/outputs/apk/debug/app-debug.apk

INSTALL ON THE OPPO FIND X8 PRO
   Copy app-debug.apk to the phone, tap it, allow "install unknown apps"
   for your file manager/browser when asked.

FIRST RUN
   Tap "Download AI model (208 MB)" once (use Wi-Fi). If the download fails,
   download lama_fp32.onnx from huggingface.co/Carve/LaMa-ONNX on any device
   and use "Import model file".

USE
   Open video -> Auto-scan (or paint with Brush / Box, Erase to fix;
   2 fingers = zoom/pan) -> Preview frame -> Remove watermark & save.
   Result goes to Movies/WatermarkRemover in your gallery.
   Use Trim (start/end seconds) to test on a short piece first.

EXPECT IT TO BE SLOW
   The AI runs on the phone CPU: roughly 1-4 seconds per frame per marked
   area (the app shows a live estimate). A 10 s / 30 fps clip = 300 frames.
   Keep the screen on and the app in the foreground. On ColorOS also set
   Settings > Battery > Watermark Remover > allow background activity, and
   avoid thermal throttling (no case, not charging while processing).

QUALITY
   Only the marked pixels are rebuilt; everything else is copied from the
   decoder untouched. Resolution, aspect ratio, rotation, frame timing and
   audio are preserved. The video is re-encoded with the hardware encoder
   at 1.1x / 1.6x / 2.5x the source bitrate (pick in "Output").
