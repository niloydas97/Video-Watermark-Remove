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
   The GitHub build creates the two model files (migan_gen_512.onnx and migan_gen_512_npu.onnx)
   by itself and bundles them inside the APK: nothing to download, works offline.
   (Android Studio route: copy both .onnx files into app/src/main/assets/ before building.)
   Fallback: import the files by hand with "I already have the model file".
   Import migan_gen_512.onnx first; then migan_gen_512_npu.onnx (optional, enables the NPU test).

USE
   Open video -> Auto-scan (or paint with Brush / Box, Erase to fix;
   2 fingers = zoom/pan) -> Preview frame -> Remove watermark & save.
   Result goes to Movies/WatermarkRemover in your gallery.
   Use Trim (start/end seconds) to test on a short piece first.

SPEED (v3)
   The model is now the bare MI-GAN neural network (tools/make_generator.py cuts it out of the
   official pipeline file, fixes its shape and folds constants into the weights: same output,
   ~40% less CPU work), plus an NPU-friendly build for the MediaTek NPU (NNAPI).
   On first launch the app tests CPU / XNNPACK / NPU and keeps the fastest.
   Settings > Speed vs exactness decides how often the AI really runs:
     Every frame  - AI on all frames (slowest)
     Precise      - reuse only when the surroundings are nearly identical
     Balanced     - reuse when they barely changed (recommended)
     Fast         - reuse through small movements
   The progress card shows how long each AI call really takes on your phone.
   Keep the screen on and the app open while processing.

QUALITY
   Only the marked pixels are rebuilt; everything else is copied from the
   decoder untouched. Resolution, aspect ratio, rotation, frame timing and
   audio are preserved. The video is re-encoded with the hardware encoder
   at 1.1x / 1.6x / 2.5x the source bitrate (pick in "Output").
