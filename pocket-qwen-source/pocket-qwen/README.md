# Pocket Qwen

An Android chat app that runs **Qwen2.5 1.5B / 3B Instruct (Q4_K_M GGUF)** fully on‑device
using [llama.cpp](https://github.com/ggml-org/llama.cpp). No server, no API key. Once the
model is downloaded, the app works offline.

- Kotlin + Jetpack Compose UI with streaming replies, a stop button, and a tokens/sec readout
- llama.cpp `v1.0.0` built with the NDK. The build downloads it automatically, so there's nothing to vendor.
- All ARM CPU variants are built (dotprod, i8mm, SVE2, SME), plus KleidiAI kernels. The
  best variant for your phone is picked at runtime.
- In‑app model downloader with resume, or import any `.gguf` from your phone
- Multi‑turn chat that reuses the KV cache, with automatic context shifting when the 4K window fills
- GitHub Actions builds the APK for you, so you don't need Android Studio installed

## Models

| Model | File | Size | Phone RAM |
|---|---|---|---|
| Qwen2.5 1.5B Instruct | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | ~1.1 GB | 4 GB+ |
| Qwen2.5 3B Instruct   | `qwen2.5-3b-instruct-q4_k_m.gguf`   | ~2.1 GB | 6 GB+ |

The files are downloaded from the official `Qwen/Qwen2.5-*-Instruct-GGUF` repos on Hugging Face.
Typical speed on a 2023+ flagship (Snapdragon 8 Gen 2/3) is roughly 15–30 tok/s for 1.5B and
8–15 tok/s for 3B. Older phones are slower.

## Get the APK (no local setup)

1. Put these files in a GitHub repo, either with `git push` or by dragging the folder onto
   **Add file → Upload files** on github.com. Make sure the hidden `.github/` folder is included.
2. Open **Actions → Build APK**. Every push to `main` builds an APK; download it from the run's
   **Artifacts** (`PocketQwen-apk`). The first build takes about 15–25 min because it compiles
   llama.cpp for two ABIs. Later builds are faster.
3. To make a GitHub Release with the APK attached, push a tag:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
   On github.com you can do the same with **Releases → Draft a new release → choose a tag `v0.1.0`**.
4. Copy the APK to your phone and install it. You'll need to allow "install unknown apps".

> The release APK is signed with the Android **debug key** so that it installs directly. Before
> publishing to Google Play, add your own `signingConfig` in `app/build.gradle.kts`.

## Build locally

Requirements: Android Studio (or the SDK command‑line tools), **NDK 29.0.13113456**, CMake 3.31.6, and JDK 17.

```bash
git clone <your-repo-url>
cd pocket-qwen
./gradlew assembleRelease        # or open the folder in Android Studio and press Run
adb install app/build/outputs/apk/release/app-release.apk
```

The first build downloads llama.cpp (about 30 MB) from GitHub. To pin a local checkout instead, put it at `third_party/llama.cpp`.

## Project layout

```
app/src/main/cpp/qwen_jni.cpp    JNI bridge: load model, chat template, prefill, sampling, context shift
app/src/main/cpp/CMakeLists.txt  builds llama.cpp + the bridge
app/src/main/java/dev/pocketqwen/
  LlamaBridge.kt                 JNI declarations
  LlamaEngine.kt                 coroutine API: every native call runs on one thread, replies come back as a Flow<String>
  ModelStore.kt                  model catalog, resumable downloader, .gguf import
  ChatViewModel.kt               app state
  ui/App.kt                      Compose screens (models, chat, settings)
.github/workflows/android.yml    CI: builds the APK and attaches it to tag releases
```

## Notes

- **Debug builds stay fast.** CMake is forced to `Release` for every variant, because an
  unoptimized ggml is 10–50× slower.
- **Updating llama.cpp:** change `LLAMA_CPP_TAG` in `app/src/main/cpp/CMakeLists.txt`.
  The C API changes often, so rebuild and fix `qwen_jni.cpp` if needed.
- **Other models:** any chat GGUF whose template llama.cpp recognizes (Llama 3, Phi‑3, Gemma, SmolLM…)
  should work through "Import .gguf". For 1–3B models, Q4_K_M or Q4_0 is a good quantization.
- Downloads run while the app is open. If you leave the app, tap Download again to resume.
