# llama.cpp chat engine (Vanta)

A **chat-only**, production llama.cpp stack that targets **TTFT / tokens-sec faster
than the current MediaPipe / LiteRT-LM on-device path**. Analysis and notifications
keep using the existing stack — this engine drives **only chat**.

## Design (matches the spec)

| Concern | Setting |
|---|---|
| GPU | **Vulkan first** (`LLAMA_VULKAN=ON`); OpenCL off |
| CPU | `max(1, physical_cores - 2)` threads, `-march=armv8.2-a+dotprod+fp16 -mfpu=neon` |
| Memory | `n_ctx=1024`, `mmap=true`, **quantized KV cache** (`type_k/v = Q8_0`) |
| Attention | **Flash Attention** on when supported (`flash_attn=true`) |
| Models | **Q4_K_M / IQ4_NL GGUF** (1B–1.7B recommended) |
| Native | Kotlin + JNI + C++ (`vanta_llama_jni.cpp`) |
| Build | `-O3`, `-flto` (LTO), `-ffast-math`, ARM64-only |
| ABI | **arm64-v8a only** → small APK, no per-ABI bloat |

## Why this is gated

It currently builds **with the existing setup**; the native library only compiles
when you opt in with `-Pllama=true`, because it needs an **NDK + CMake** and the
**llama.cpp source (submodule)**. Your `local.properties` SDK
(`~/.bubblewrap/android_sdk`) has **no NDK/CMake**, so the native build is off by
default and the app still works (chat falls back to LiteRT-LM / cloud).

## Enable it

1. **Install NDK + CMake** (from a full SDK / `sdkmanager`, or Android Studio):
   ```bash
   sdkmanager "ndk;27.1.12297006" "cmake;3.22.1"
   ```
   Update `local.properties` → `ndk.dir=/path/to/ndk/27.1.12297006` (or let Gradle
   resolve it).

2. **Pull the llama.cpp submodule** (pinned):
   ```bash
   git submodule update --init --depth 1
   cd app/src/main/cpp/llama.cpp && git fetch --depth 1 origin <tag> && git checkout <tag>
   ```
   Then open `app/src/main/cpp/vanta_llama_jni.cpp` and reconcile the sampler / arena
   calls with the pinned `llama.h` (the sampler API has changed across versions).

3. **Download a chat GGUF** into the app's download dir (this is where
   `VantaLllamaEngine.modelFile()` looks):
   ```
   /sdcard/Android/data/com.vanta.app/files/Download/vanta-chat.gguf
   ```
   Chosen model: **`Qwen_Qwen3-VL-2B-Instruct-Q4_K_M.gguf`** (~1.1 GB) — strong at
   instruction-following / JSON, so it serves the whole app (chat + analysis + Vantix
   + notifications). `VantaLllamaEngine.MODEL_FILENAME` and
   `ModelDownloadManager.DEFAULT_MODEL_URL` already point at it. Vision would need an
   extra `mmproj` file — we treat it as text-only unless you add one.
   The JNI wrapper is reconciled to the vendored llama.cpp (`b10680`): `llama_init_from_model`,
   `flash_attn_type`, `GGML_TYPE_Q8_0` KV cache, vocab-based tokenizer, `llama_vocab_is_eog`.

4. **Build with the flag**:
   ```bash
   ./gradlew -Pllama=true :app:assembleRelease
   ```

## Storage

- Only **arm64-v8a** `.so` + one GGUF. A 1B Q4_K_M model keeps the footprint
  ~0.8–1 GB. The model lives in app-specific external storage (auto-cleared on
  uninstall) and is gated by `isModelDownloaded()` (≥ 50 MB) so a partial download
  never activates chat.

## How it decides

`VantaGemmaEngine.generateChatResponseStreaming` uses llama.cpp **only when**
`chat provider == ON_DEVICE && VantaLllamaEngine.isAvailable()`
(native `.so` present **and** model file valid). Otherwise it falls back to the
existing LiteRT-LM / cloud path — the app never breaks.

## TODO
- Add a "Download model" row for the GGUF in Settings (uses `ModelDownloadManager`)
  so the user can pull it in-app.
