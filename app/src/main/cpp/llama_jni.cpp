#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <exception>
#include <mutex>
#include <memory>
#include <new>
#include <string>
#include <vector>
#include "llama.h"

namespace {
std::mutex lock;
std::atomic<bool> cancelled{false};
llama_model *model = nullptr;
llama_context *ctx = nullptr;
int generated = 0;
bool backend_ready = false;
struct Utf {
    JNIEnv *env;
    jstring value;
    const char *chars;
    Utf(JNIEnv *e, jstring v) : env(e), value(v), chars(v ? e->GetStringUTFChars(v, nullptr) : nullptr) {}
    ~Utf() { if (chars) env->ReleaseStringUTFChars(value, chars); }
};
void release() {
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
    if (backend_ready) { llama_backend_free(); backend_ready = false; }
}
bool abort_decode(void *) { return cancelled.load(); }
bool load_progress(float, void *) { return !cancelled.load(); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dc_andagent_llama_LlamaNative_load(JNIEnv *env, jobject, jstring path, jint threads) {
    Utf file(env, path);
    if (!file.chars) return env->NewStringUTF("无法读取模型路径");
    std::lock_guard<std::mutex> guard(lock);
    release();
    try {
    llama_backend_init();
    backend_ready = true;
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.progress_callback = load_progress;
    model = llama_model_load_from_file(file.chars, mp);
    if (!model) { release(); return env->NewStringUTF("GGUF 模型加载失败或已取消"); }
    auto cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_threads = threads > 0 ? threads : 2;
    cp.n_threads_batch = cp.n_threads;
    cp.abort_callback = abort_decode;
    ctx = llama_init_from_model(model, cp);
    if (!ctx) { release(); return env->NewStringUTF("上下文分配失败（内存不足）"); }
    return env->NewStringUTF("");
    } catch (const std::bad_alloc &) {
        release();
        return env->NewStringUTF("native 内存分配失败");
    } catch (const std::exception &) {
        release();
        return env->NewStringUTF("GGUF 模型加载异常");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dc_andagent_llama_LlamaNative_generate(JNIEnv *env, jobject, jstring input, jint max_tokens, jfloat temperature) {
    Utf prompt(env, input);
    if (!prompt.chars) return nullptr;
    std::lock_guard<std::mutex> guard(lock);
    generated = 0;
    if (!model || !ctx) return nullptr;
    try {
    const auto *vocab = llama_model_get_vocab(model);
    const int n = llama_tokenize(vocab, prompt.chars, static_cast<int>(strlen(prompt.chars)), nullptr, 0, false, true);
    if (n >= 0 || -n >= 2048 || -n <= 0) return nullptr;
    std::vector<llama_token> tokens(static_cast<size_t>(-n));
    if (llama_tokenize(vocab, prompt.chars, static_cast<int>(strlen(prompt.chars)), tokens.data(), -n, false, true) < 0) return nullptr;
    llama_memory_clear(llama_get_memory(ctx), true);
    llama_batch batch;
    for (size_t offset = 0; offset < tokens.size(); offset += 512) {
        const int count = static_cast<int>(std::min<size_t>(512, tokens.size() - offset));
        batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(ctx, batch) != 0) return nullptr;
    }
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    if (!sampler) return nullptr;
    if (temperature <= 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    else {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    std::string result;
    for (int i = 0; i < max_tokens && !cancelled; ++i) {
        llama_token token = llama_sampler_sample(sampler.get(), ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[256];
        const int size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (size > 0) result.append(piece, size);
        else if (size < 0 && size > -4096) {
            std::vector<char> large_piece(static_cast<size_t>(-size));
            const int written = llama_token_to_piece(vocab, token, large_piece.data(), -size, 0, false);
            if (written > 0) result.append(large_piece.data(), written);
        }
        ++generated;
        if (static_cast<int>(tokens.size()) + generated >= 2048) break;
        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, batch) != 0) break;
    }
    if (cancelled) return nullptr;
    return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc &) {
        return nullptr;
    } catch (const std::exception &) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dc_andagent_llama_LlamaNative_generatedTokens(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    return generated;
}
extern "C" JNIEXPORT void JNICALL
Java_com_dc_andagent_llama_LlamaNative_cancel(JNIEnv *, jobject) { cancelled = true; }
extern "C" JNIEXPORT void JNICALL
Java_com_dc_andagent_llama_LlamaNative_resetCancellation(JNIEnv *, jobject) { cancelled = false; }
extern "C" JNIEXPORT void JNICALL
Java_com_dc_andagent_llama_LlamaNative_unload(JNIEnv *, jobject) {
    cancelled = true;
    std::lock_guard<std::mutex> guard(lock);
    release();
    cancelled = false;
}
