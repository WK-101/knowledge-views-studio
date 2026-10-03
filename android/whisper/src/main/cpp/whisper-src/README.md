# Vendored whisper.cpp source

On-device speech recognition engine for `:voice-addon`, **built from source** (no third-party
prebuilt binary) by this module's CMake via the Android NDK.

- **Upstream:** https://github.com/ggml-org/whisper.cpp
- **Pinned tag:** `v1.7.5` (commit `51c6961c7b64b406833f4b6a4a20e67142f69225`)
- **License:** MIT (whisper.cpp and ggml)

## What is vendored

A curated subset sufficient for a CPU-only Android build:

- `src/` — whisper.cpp core
- `include/` — whisper public headers
- `ggml/include/` — ggml public headers
- `ggml/src/` — ggml top-level sources + `ggml-cpu/` (the CPU backend only)

The GPU/other backends (CUDA, Metal, Vulkan, SYCL, OpenCL, BLAS, etc.) are intentionally omitted —
they are `#ifdef`-guarded and unused in a `GGML_USE_CPU` build. The exact compiled file list is in
`../../jni/whisper/CMakeLists.txt`.

## JNI

`../../jni/whisper/jni.c` is adapted from whisper.cpp's `examples/whisper.android` JNI, with
`fullTranscribe` extended to take a bias prompt (whisper `initial_prompt`) and to force English,
text-only output. The Kotlin API is `com.whispercpp.whisper.WhisperContext`.

## Models

No model is bundled. The user imports a GGML model (e.g. `ggml-tiny.en-q5_1.bin`, 31 MB, or
`ggml-base.en-q5_1.bin`, 57 MB) from https://huggingface.co/ggerganov/whisper.cpp via the addon's
SAF import (no network in the addon).
