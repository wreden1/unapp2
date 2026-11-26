# Offline image generation — Android CPU milestone

## Goal

Add a completely local image generator to Character Studio. The image engine is
kept separate from the existing Qwen chat engine so that image-generation work
does not change chat decoding, history, or repetition handling.

## Device target

The first target is Android `arm64-v8a`, using the CPU backend. The Galaxy S22
Ultra sold in Sweden uses the Exynos variant in this project context; the
upstream OpenCL path is documented for Adreno GPUs only, so this build
intentionally disables OpenCL and Vulkan rather than assuming either GPU path
will work on this device.

## First milestone: prove the native library builds

The workflow
`.github/workflows/android-image-engine.yml` checks out
`stable-diffusion.cpp` at a fixed commit and builds its shared C library with
the Android NDK for `arm64-v8a`. The library is not yet linked into the app.
The workflow is deliberately a separate build so failures cannot break the
existing chat APK.

Upstream exposes an opaque C API in `stable-diffusion.h`: create a context
from model options, generate an image, and read the resulting U8 image tensor.
The API changed recently, so the app integration must use this pinned header
and API rather than old Android examples.

## Follow-on work after the build passes

1. Add a JNI wrapper around the pinned C API and return image pixels safely to
   Kotlin.
2. Add a separate, checksum-verified model downloader and storage-space check.
3. Add a chat image message type without changing the existing text-history
   format.
4. Build a prompt from the character profile and recent conversation, then
   show the generated image in the conversation.
5. Verify an actual generation on the Exynos Galaxy S22 Ultra before calling
   the feature complete.

## Important limits

- This first workflow proves only that the native engine compiles for Android
  arm64; it does not prove that a particular model fits in RAM or runs at an
  acceptable speed on the phone.
- CPU generation may take several minutes. Model files will require several
  gigabytes of free storage depending on the selected model.
- Character likeness consistency needs a compatible reference-image workflow;
  text-to-image alone cannot guarantee that the same face is reproduced.
- The image model is downloaded separately once. After download, generation
  itself must not make network requests.
