#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include "llama.h"
#include "ggml.h"

/*
 * Vanta chat-only llama.cpp bindings.
 *
 * NOTE: This targets the recent llama.cpp C API (llama_sampler_* chain). Pin the
 * submodule to a specific tag and adjust the sampler/arena calls if the pinned
 * version differs. Only the chat path uses this engine; analysis/notifications
 * keep using the existing MediaPipe/LiteRT-LM stack.
 */

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static llama_sampler* g_smpl  = nullptr;
static int            g_stream_tokens = 0;
static int            g_stream_max    = 0;
static bool           g_stream_done   = false;
static bool           g_backend_inited = false;
static std::mutex     g_mutex;

namespace {

// Route llama.cpp / ggml logs (and abort messages) into Android logcat so we can
// diagnose decoder failures on-device instead of guessing from a silent crash.
void ggml_log_cb(enum ggml_log_level level, const char* text, void*) {
    if (!text) return;
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_INFO;  break;
    }
    __android_log_print(prio, "llamacpp", "%s", text);
}

std::vector<llama_token> tokenize(llama_model* model, const std::string& text, bool add_special) {
    const llama_vocab* vocab = llama_model_get_vocab(model);
    std::vector<llama_token> out(text.size() + 8, 0);
    const int32_t n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), (int32_t) out.size(), add_special, true);
    if (n < 0) return {};
    out.resize((size_t) n);
    return out;
}

std::string detokenize(llama_model* model, llama_token tok) {
    const llama_vocab* vocab = llama_model_get_vocab(model);
    char buf[512];
    const int32_t n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n <= 0) return "";
    return std::string(buf, (size_t) n);
}

bool is_eog(llama_model* model, llama_token tok) {
    return llama_vocab_is_eog(llama_model_get_vocab(model), tok);
}

// Render a system+user turn using the model's own chat template (Qwen3 needs this).
std::string apply_chat_template(llama_model* model, const std::string& system, const std::string& user) {
    const llama_chat_message msgs[] = {
        {"system", system.c_str()},
        {"user",   user.c_str()},
    };
    const char* tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) return system + "\n\n" + user;
    int32_t len = llama_chat_apply_template(tmpl, msgs, 2, true, nullptr, 0);
    if (len <= 0) return system + "\n\n" + user;
    std::string out((size_t) len + 1, '\0');
    int32_t n = llama_chat_apply_template(tmpl, msgs, 2, true, out.data(), len + 1);
    if (n < 0) return system + "\n\n" + user;
    out.resize((size_t) n);
    return out;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_vanta_app_data_ai_VantaLllamaEngine_nativeInit(
        JNIEnv* env, jobject, jstring modelPath, jint nThreads, jint nThreadsBatch, jint nCtx, jint nBatch, jboolean flashAttn) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ggml_log_set(ggml_log_cb, nullptr);
    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
    }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }   // fresh context each turn; the model stays cached across turns
    if (!g_model) {
        const char* mp = env->GetStringUTFChars(modelPath, nullptr);
        llama_model_params mp_ = llama_model_default_params();
        mp_.n_gpu_layers = -1;         // offload all layers to GPU (negative = all)
        g_model = llama_model_load_from_file(mp, mp_);
        env->ReleaseStringUTFChars(modelPath, mp);
        if (!g_model) return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = nCtx;
    cp.n_threads       = nThreads;        // generation threads (fewer = better on a small device)
    cp.n_threads_batch = nThreadsBatch;   // prompt-eval threads (more = faster TTFT)
    cp.n_batch         = nBatch;
    cp.type_k          = GGML_TYPE_F16; // F16 KV — Q8_0 quantized KV is unsupported with flash attention on Vulkan
    cp.type_v          = GGML_TYPE_F16;
    cp.flash_attn_type = flashAttn ? LLAMA_FLASH_ATTN_TYPE_AUTO : LLAMA_FLASH_ATTN_TYPE_DISABLED; // let llama.cpp pick a supported path
    g_ctx = llama_init_from_model(g_model, cp);
    return reinterpret_cast<jlong>(g_ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vanta_app_data_ai_VantaLllamaEngine_nativeGenerate(
        JNIEnv* env, jobject, jstring system, jstring user, jint maxTokens, jfloat temperature, jfloat topP, jint topK, jint seed) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx) return env->NewStringUTF("");
    const char* sc = env->GetStringUTFChars(system, nullptr);
    const char* uc = env->GetStringUTFChars(user, nullptr);
    std::string sys(sc ? sc : ""), usr(uc ? uc : "");
    env->ReleaseStringUTFChars(system, sc);
    env->ReleaseStringUTFChars(user, uc);

    const std::string prompt = apply_chat_template(g_model, sys, usr);
    auto toks = tokenize(g_model, prompt, false);
    if (toks.empty()) return env->NewStringUTF("");
    llama_batch batch = llama_batch_get_one(toks.data(), (int) toks.size());
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("");

    auto* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature > 0 ? temperature : 0.6f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));

    std::string out;
    out.reserve((size_t) maxTokens * 4);
    for (int i = 0; i < maxTokens; ++i) {
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        if (t < 0 || is_eog(g_model, t)) break;
        out += detokenize(g_model, t);
        llama_batch one = llama_batch_get_one(&t, 1);
        if (llama_decode(g_ctx, one) != 0) break;
    }
    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vanta_app_data_ai_VantaLllamaEngine_nativeStreamInit(
        JNIEnv* env, jobject, jstring system, jstring user, jint maxTokens, jfloat temperature, jfloat topP, jint topK, jint seed) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || !g_model) return JNI_FALSE;
    const char* sc = env->GetStringUTFChars(system, nullptr);
    const char* uc = env->GetStringUTFChars(user, nullptr);
    std::string sys(sc ? sc : ""), usr(uc ? uc : "");
    env->ReleaseStringUTFChars(system, sc);
    env->ReleaseStringUTFChars(user, uc);

    const std::string prompt = apply_chat_template(g_model, sys, usr);
    auto toks = tokenize(g_model, prompt, false);
    if (toks.empty()) return JNI_FALSE;
    llama_batch batch = llama_batch_get_one(toks.data(), (int) toks.size());
    if (llama_decode(g_ctx, batch) != 0) return JNI_FALSE;

    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.95f, 1));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_temp(temperature > 0 ? temperature : 0.6f));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_dist((uint32_t) seed));

    g_stream_max = maxTokens;
    g_stream_tokens = 0;
    g_stream_done = false;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vanta_app_data_ai_VantaLllamaEngine_nativeStreamNext(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || !g_model || !g_smpl || g_stream_done || g_stream_tokens >= g_stream_max) {
        return env->NewStringUTF("");
    }
    llama_token t = llama_sampler_sample(g_smpl, g_ctx, -1);
    if (t < 0 || is_eog(g_model, t)) {
        g_stream_done = true;
        return env->NewStringUTF("");
    }
    std::string piece = detokenize(g_model, t);
    llama_batch one = llama_batch_get_one(&t, 1);
    if (llama_decode(g_ctx, one) != 0) {
        g_stream_done = true;
        return env->NewStringUTF(piece.c_str());
    }
    g_stream_tokens++;
    return env->NewStringUTF(piece.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_vanta_app_data_ai_VantaLllamaEngine_nativeStreamRelease(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    g_stream_done = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vanta_app_data_ai_VantaLllamaEngine_nativeRelease(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    // Keep the expensive model (and backend) loaded across turns; only drop the per-turn
    // context + sampler so the next message doesn't re-read the 1.1 GB GGUF from disk.
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_ctx)  { llama_free(g_ctx); g_ctx = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vanta_app_data_ai_VantaLllamaEngine_nativeUnload(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_ctx)  { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model){ llama_model_free(g_model); g_model = nullptr; }
    if (g_backend_inited) { llama_backend_free(); g_backend_inited = false; }
    g_stream_tokens = 0;
    g_stream_done   = false;
}
