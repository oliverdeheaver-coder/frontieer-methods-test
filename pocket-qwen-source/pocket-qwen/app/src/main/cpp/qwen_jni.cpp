// JNI bridge between the Kotlin app and llama.cpp.
//
// One model / one conversation at a time. Every function here must be called from the
// same thread (the Kotlin side uses a single-threaded dispatcher), so there is no locking.
//
// Strings cross the boundary as UTF-8 byte arrays rather than jstring: JNI's
// NewStringUTF/GetStringUTFChars use "modified UTF-8", which mangles emoji and other
// 4-byte characters that the model happily produces.

#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define TAG "PocketQwen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct ChatMsg {
    std::string role;
    std::string content;
};

struct State {
    llama_model     * model   = nullptr;
    llama_context   * ctx     = nullptr;
    llama_sampler   * sampler = nullptr;
    llama_batch_ext * batch   = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string chat_template;   // empty => model has none, fall back to "chatml"

    // conversation
    std::vector<ChatMsg> msgs;
    size_t      formatted_consumed = 0;  // bytes of the formatted chat already in the KV cache
    llama_pos   n_past = 0;              // tokens in the KV cache
    llama_pos   n_keep = 0;              // tokens of the system prompt (never shifted out)

    // current turn
    bool        in_turn = false;
    int         n_generated = 0;
    int         max_tokens = 0;
    std::string assistant_text;          // full reply so far (committed to msgs at end of turn)
    std::string pending_utf8;            // partial multi-byte char waiting for its tail
    std::string last_error;
};

State g;

std::string bytes_to_string(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (!s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                                reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

jstring ascii_jstring(JNIEnv * env, const std::string & s) {
    // only used for error / info text we produce ourselves (ASCII-safe)
    return env->NewStringUTF(s.c_str());
}

void android_log(enum ggml_log_level level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, "llama.cpp", text);
}

// Returns how many leading bytes of s form complete UTF-8 sequences.
size_t complete_utf8_prefix(const std::string & s) {
    size_t i = s.size();
    // look back at most 3 bytes for the start of a possibly-incomplete sequence
    for (size_t back = 1; back <= 3 && back <= s.size(); ++back) {
        const unsigned char c = static_cast<unsigned char>(s[s.size() - back]);
        if ((c & 0xC0) == 0x80) continue;          // continuation byte, keep looking
        size_t need = 1;
        if      ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        if (need > back) i = s.size() - back;      // sequence is cut off
        break;
    }
    return i;
}

std::string format_chat(const std::vector<ChatMsg> & msgs, bool add_assistant) {
    std::vector<llama_chat_message> cm;
    cm.reserve(msgs.size());
    for (const auto & m : msgs) cm.push_back({m.role.c_str(), m.content.c_str()});

    const char * tmpl = g.chat_template.empty() ? "chatml" : g.chat_template.c_str();
    size_t total = 0;
    for (const auto & m : msgs) total += m.content.size();
    std::vector<char> buf(std::max<size_t>(1024, total * 2 + 512));

    int32_t n = llama_chat_apply_template(tmpl, cm.data(), cm.size(), add_assistant,
                                          buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(n));
        n = llama_chat_apply_template(tmpl, cm.data(), cm.size(), add_assistant,
                                      buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) return {};
    return std::string(buf.data(), static_cast<size_t>(n));
}

std::vector<llama_token> tokenize(const std::string & text, bool add_special) {
    int n = -llama_tokenize(g.vocab, text.data(), static_cast<int32_t>(text.size()),
                            nullptr, 0, add_special, /*parse_special*/ true);
    std::vector<llama_token> toks(static_cast<size_t>(std::max(n, 0)));
    if (n > 0) {
        llama_tokenize(g.vocab, text.data(), static_cast<int32_t>(text.size()),
                       toks.data(), n, add_special, true);
    }
    return toks;
}

// Drop the older half of the conversation (keeping the system prompt) to make room.
bool shift_context(int needed) {
    llama_memory_t mem = llama_get_memory(g.ctx);
    if (!llama_memory_can_shift(mem)) return false;
    const int n_ctx = static_cast<int>(llama_n_ctx(g.ctx));
    int n_discard = (g.n_past - g.n_keep) / 2;
    // make sure we actually free enough
    n_discard = std::max(n_discard, g.n_past + needed - n_ctx + 16);
    n_discard = std::min(n_discard, static_cast<int>(g.n_past - g.n_keep));
    if (n_discard <= 0) return false;
    LOGW("context full: discarding %d tokens", n_discard);
    llama_memory_seq_rm (mem, 0, g.n_keep, g.n_keep + n_discard);
    llama_memory_seq_add(mem, 0, g.n_keep + n_discard, g.n_past, -n_discard);
    g.n_past -= n_discard;
    return true;
}

// Feed tokens into the KV cache. Logits are requested only for the very last token.
bool decode(const std::vector<llama_token> & toks, bool want_last_logits) {
    const int n_ctx   = static_cast<int>(llama_n_ctx(g.ctx));
    const int n_batch = static_cast<int>(llama_n_batch(g.ctx));
    const int total   = static_cast<int>(toks.size());

    if (total >= n_ctx - g.n_keep - 8) {
        g.last_error = "Message is too long for the context window.";
        return false;
    }
    if (g.n_past + total > n_ctx && !shift_context(total)) {
        g.last_error = "Context window is full. Start a new chat.";
        return false;
    }

    for (int i = 0; i < total; i += n_batch) {
        const int n = std::min(n_batch, total - i);
        llama_batch_ext_clear(g.batch);
        for (int j = 0; j < n; ++j) {
            const int32_t idx = llama_batch_ext_add_token(g.batch, 0, toks[i + j]);
            const llama_pos pos = g.n_past + j;
            llama_batch_ext_set_pos(g.batch, idx, &pos);
        }
        if (want_last_logits && i + n == total) {
            llama_batch_ext_set_output_logits(g.batch, n - 1, true);
        }
        const int32_t ret = llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, g.batch);
        if (ret != 0) {
            g.last_error = "llama_process failed (" + std::to_string(ret) + ")";
            LOGE("%s", g.last_error.c_str());
            return false;
        }
        g.n_past += n;
    }
    return true;
}

void free_sampler() {
    if (g.sampler) { llama_sampler_free(g.sampler); g.sampler = nullptr; }
}

void build_sampler(float temperature, float top_p) {
    free_sampler();
    auto sp = llama_sampler_chain_default_params();
    g.sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(g.sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g.sampler, llama_sampler_init_penalties(
            llama_vocab_n_tokens(g.vocab), /*last_n*/ 64, /*repeat*/ 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(g.sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(g.sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(g.sampler, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(g.sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(g.sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
}

void finish_turn() {
    if (!g.in_turn) return;
    g.assistant_text += g.pending_utf8;
    g.pending_utf8.clear();
    // The KV cache now holds everything up to the end of the reply text (the end-of-turn
    // token itself was sampled but not decoded). Record that so the next user turn feeds
    // the template's closing tokens ("<|im_end|>\n") before the new message.
    const std::string with_prompt = format_chat(g.msgs, /*add_assistant*/ true);
    g.formatted_consumed = with_prompt.size() + g.assistant_text.size();
    g.msgs.push_back({"assistant", g.assistant_text});
    g.assistant_text.clear();
    g.in_turn = false;
}

void free_all() {
    free_sampler();
    if (g.batch) { llama_batch_ext_free(g.batch); g.batch = nullptr; }
    if (g.ctx)   { llama_free(g.ctx);             g.ctx = nullptr; }
    if (g.model) { llama_model_free(g.model);     g.model = nullptr; }
    g.vocab = nullptr;
    g.msgs.clear();
    g.n_past = g.n_keep = 0;
    g.formatted_consumed = 0;
    g.in_turn = false;
}

}  // namespace

#define JNI_FN(name) JNICALL Java_dev_pocketqwen_LlamaBridge_##name

extern "C" {

JNIEXPORT void JNI_FN(nativeInit)(JNIEnv * env, jobject, jstring jlib_dir) {
    llama_log_set(android_log, nullptr);
    const char * dir = env->GetStringUTFChars(jlib_dir, nullptr);
    LOGI("loading ggml backends from %s", dir);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(jlib_dir, dir);
    llama_backend_init();
}

JNIEXPORT jstring JNI_FN(nativeSystemInfo)(JNIEnv * env, jobject) {
    return ascii_jstring(env, llama_print_system_info());
}

// Returns null on success, or an error message.
JNIEXPORT jstring JNI_FN(nativeLoad)(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    free_all();

    const char * path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU only on Android
    g.model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g.model) return ascii_jstring(env, "Could not load the model file (corrupt or unsupported GGUF?)");

    g.vocab = llama_model_get_vocab(g.model);
    const char * tmpl = llama_model_chat_template(g.model, nullptr);
    g.chat_template = tmpl ? tmpl : "";

    int threads = n_threads > 0 ? n_threads
                                : std::clamp(static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN)) - 2, 2, 6);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(std::min<int>(n_ctx, llama_model_n_ctx_train(g.model)));
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;
    g.ctx = llama_init_from_model(g.model, cp);
    if (!g.ctx) {
        free_all();
        return ascii_jstring(env, "Could not create the inference context (out of memory?)");
    }
    g.batch = llama_batch_ext_init(g.ctx);
    build_sampler(0.7f, 0.9f);

    char desc[128];
    llama_model_desc(g.model, desc, sizeof(desc));
    LOGI("loaded %s, n_ctx=%u, threads=%d", desc, llama_n_ctx(g.ctx), threads);
    return nullptr;
}

// Clears the conversation and pre-fills the system prompt. Returns null or an error.
JNIEXPORT jstring JNI_FN(nativeReset)(JNIEnv * env, jobject, jbyteArray jsystem) {
    if (!g.ctx) return ascii_jstring(env, "No model loaded");
    llama_memory_clear(llama_get_memory(g.ctx), true);
    g.msgs.clear();
    g.n_past = g.n_keep = 0;
    g.formatted_consumed = 0;
    g.in_turn = false;
    g.assistant_text.clear();
    g.pending_utf8.clear();

    const std::string system = bytes_to_string(env, jsystem);
    if (system.empty()) return nullptr;

    g.msgs.push_back({"system", system});
    const std::string formatted = format_chat(g.msgs, false);
    if (formatted.empty()) return ascii_jstring(env, "Chat template failed");
    if (!decode(tokenize(formatted, true), false)) return ascii_jstring(env, g.last_error);
    g.formatted_consumed = formatted.size();
    g.n_keep = g.n_past;
    return nullptr;
}

// Adds the user message and processes the prompt. Then call nativeNextToken until it returns null.
JNIEXPORT jstring JNI_FN(nativeStartTurn)(JNIEnv * env, jobject, jbyteArray juser,
                                          jfloat temperature, jfloat top_p, jint max_tokens) {
    if (!g.ctx) return ascii_jstring(env, "No model loaded");
    finish_turn();  // in case the previous turn was abandoned

    g.msgs.push_back({"user", bytes_to_string(env, juser)});
    const std::string formatted = format_chat(g.msgs, true);
    if (formatted.size() < g.formatted_consumed) {
        g.msgs.pop_back();
        return ascii_jstring(env, "Chat template failed");
    }
    const std::string delta = formatted.substr(g.formatted_consumed);
    const bool first = g.n_past == 0;
    if (!decode(tokenize(delta, first), true)) {
        g.msgs.pop_back();
        return ascii_jstring(env, g.last_error);
    }
    g.formatted_consumed = formatted.size();

    build_sampler(temperature, top_p);
    g.in_turn = true;
    g.n_generated = 0;
    g.max_tokens = max_tokens > 0 ? max_tokens : 1024;
    g.assistant_text.clear();
    g.pending_utf8.clear();
    return nullptr;
}

// Returns the next chunk of UTF-8 text (possibly empty), or null when the reply is finished.
JNIEXPORT jbyteArray JNI_FN(nativeNextToken)(JNIEnv * env, jobject) {
    if (!g.ctx || !g.in_turn) return nullptr;

    if (g.n_generated >= g.max_tokens) { finish_turn(); return nullptr; }

    const llama_token tok = llama_sampler_sample(g.sampler, g.ctx, -1);
    if (llama_vocab_is_eog(g.vocab, tok)) { finish_turn(); return nullptr; }

    char buf[256];
    const int n = llama_token_to_piece(g.vocab, tok, buf, sizeof(buf), 0, /*special*/ false);
    std::string piece = n > 0 ? std::string(buf, static_cast<size_t>(n)) : std::string();

    // feed the sampled token back so the next call can sample from it
    if (!decode({tok}, true)) {
        LOGE("decode during generation failed: %s", g.last_error.c_str());
        finish_turn();
        return nullptr;
    }
    g.n_generated++;

    g.pending_utf8 += piece;
    const size_t ok = complete_utf8_prefix(g.pending_utf8);
    std::string out = g.pending_utf8.substr(0, ok);
    g.pending_utf8.erase(0, ok);
    g.assistant_text += out;
    return string_to_bytes(env, out);
}

// Ends the current reply early (user pressed stop).
JNIEXPORT void JNI_FN(nativeStopTurn)(JNIEnv *, jobject) {
    finish_turn();
}

JNIEXPORT jint JNI_FN(nativeContextUsed)(JNIEnv *, jobject) {
    return g.n_past;
}

JNIEXPORT jint JNI_FN(nativeContextSize)(JNIEnv *, jobject) {
    return g.ctx ? static_cast<jint>(llama_n_ctx(g.ctx)) : 0;
}

JNIEXPORT void JNI_FN(nativeUnload)(JNIEnv *, jobject) {
    free_all();
}

}  // extern "C"
