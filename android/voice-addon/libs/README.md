# Vendored native libraries

## `sherpa-onnx-1.13.8-arm64.aar`

The offline streaming speech-to-text engine used by `:voice-addon` ([sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
by k2-fsa). It is **Apache-2.0** licensed, which permits redistribution; it is vendored here because
sherpa-onnx publishes no Maven artifact.

It bundles the Kotlin API (`com.k2fsa.sherpa.onnx`) plus the native libraries, and is resolved via the
`flatDir` repository declared in `settings.gradle.kts`.

### Provenance (reproducible)

Derived from the official `v1.13.8` Android AAR, slimmed to keep the addon small:

1. Download the official release AAR for `v1.13.8` from the sherpa-onnx GitHub releases.
2. Keep only the `arm64-v8a` ABI (covers effectively all modern devices; `armeabi-v7a` can be added
   later at a size cost).
3. Remove the native libraries the Kotlin/JNI path does not use — the JNI lib links only
   `libonnxruntime.so` (verified via `readelf -d`), so `libsherpa-onnx-c-api.so` and
   `libsherpa-onnx-cxx-api.so` (native C/C++ consumer libs, ~4.9 MB) are stripped:

   ```
   zip -d sherpa-onnx-1.13.8-arm64.aar \
     jni/arm64-v8a/libsherpa-onnx-c-api.so \
     jni/arm64-v8a/libsherpa-onnx-cxx-api.so
   ```

The result ships `jni/arm64-v8a/libonnxruntime.so` + `jni/arm64-v8a/libsherpa-onnx-jni.so` only.

### Model

No model is bundled. The user imports an STT model once, via SAF (no network), into the addon's
private storage; see the addon's model-import flow. `ModelStore` locates it and `SherpaSttEngine`
reports `modelReady = false` until it is present.
