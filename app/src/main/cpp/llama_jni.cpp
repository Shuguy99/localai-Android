// JNI bridge between Kotlin (dev.localai.agent.inference.LlamaBridge) and llama.cpp.
//
// Design notes:
//  * Weights are loaded once per model (mmap) and kept in a Session.
//  * A fresh llama_context is created for every generation. This costs a few
//    hundred ms but keeps KV-cache lifetime, cancellation and memory accounting
//    trivial. Prefix caching / persistent contexts are a later optimisation.
//  * All state is guarded by a per-session mutex; cancel() only flips an atomic.
//  * Text crosses the JNI boundary as real UTF-8, not Modified UTF-8, so emoji
//    and other supplementary-plane code points survive.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <random>
#include <string>
#include <unordered_set>
#include <vector>

#include "llama.h"

#define TAG "localai-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 2048;
    int n_batch = 512;
    int n_threads = 4;
    std::atomic<bool> cancel{false};
    std::mutex mu;
};

std::mutex g_registry_mu;
std::unordered_set<Session *> g_sessions;

Session * lookup(jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    std::lock_guard<std::mutex> lock(g_registry_mu);
    if (g_sessions.find(s) == g_sessions.end()) return nullptr;
    return s;
}

void append_utf8(std::string & out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::string to_utf8(JNIEnv * env, jstring js) {
    if (js == nullptr) return {};
    const jsize len = env->GetStringLength(js);
    const jchar * chars = env->GetStringChars(js, nullptr);
    if (chars == nullptr) return {};
    std::string out;
    out.reserve(static_cast<size_t>(len) * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < len) {
                const uint32_t lo = chars[i + 1];
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    ++i;
                } else {
                    cp = 0xFFFD;
                }
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        append_utf8(out, cp);
    }
    env->ReleaseStringChars(js, chars);
    return out;
}

jstring from_utf8(JNIEnv * env, const std::string & s) {
    std::vector<jchar> utf16;
    utf16.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0xFFFD;
        size_t need = 0;
        if (c < 0x80) { cp = c; need = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; need = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; need = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; need = 3; }

        if (i + need >= s.size() && need > 0) { ++i; continue; }
        bool valid = true;
        for (size_t k = 1; k <= need; ++k) {
            const unsigned char cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0) != 0x80) { valid = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!valid) { utf16.push_back(0xFFFD); ++i; continue; }
        i += need + 1;

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

// Length of the prefix that ends on a UTF-8 code point boundary.
size_t utf8_safe_len(const std::string & s, size_t limit) {
    if (limit == 0) return 0;
    size_t i = 0;
    while (i < limit) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t need = 0;
        if (c < 0x80) need = 0;
        else if ((c & 0xE0) == 0xC0) need = 1;
        else if ((c & 0xF0) == 0xE0) need = 2;
        else if ((c & 0xF8) == 0xF0) need = 3;
        else { ++i; continue; }
        if (i + need >= limit) break;
        i += need + 1;
    }
    return i;
}

std::string json_escape(const char * s) {
    std::string out;
    for (const char * p = s; p != nullptr && *p != '\0'; ++p) {
        const unsigned char c = static_cast<unsigned char>(*p);
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    return out;
}

bool decode_tokens(
        llama_context * ctx,
        const std::vector<llama_token> & tokens,
        int32_t start_pos,
        int32_t n_batch,
        bool logits_on_last) {
    const int32_t total = static_cast<int32_t>(tokens.size());
    for (int32_t begin = 0; begin < total; begin += n_batch) {
        const int32_t end = std::min(begin + n_batch, total);
        const int32_t n = end - begin;

        llama_batch batch = llama_batch_init(n, 0, 1);
        if (batch.token == nullptr) return false;

        for (int32_t i = 0; i < n; ++i) {
            batch.token[i] = tokens[begin + i];
            batch.pos[i] = start_pos + begin + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (logits_on_last && end == total && i == n - 1) ? 1 : 0;
        }
        batch.n_tokens = n;

        const int32_t rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            LOGE("llama_decode failed: %d", rc);
            return false;
        }
    }
    return true;
}

llama_sampler * build_sampler(
        const llama_vocab * vocab,
        float temperature,
        float top_p,
        int top_k,
        float repeat_penalty) {
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(sparams);

    const int n_vocab = static_cast<int>(llama_vocab_n_tokens(vocab));
    llama_sampler_chain_add(
            chain,
            llama_sampler_init_penalties(n_vocab, 64, repeat_penalty, 0.0f, 0.0f));

    if (top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    }
    if (temperature > 0.01f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        const uint32_t seed = static_cast<uint32_t>(
                std::chrono::steady_clock::now().time_since_epoch().count());
        llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }
    return chain;
}

std::once_flag g_backend_once;
std::atomic<bool> g_backend_ready{false};

void android_log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGW("%s", text); break;
        default: break; // keep INFO/DEBUG out of logcat to avoid I/O stalls
    }
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localai_agent_inference_LlamaBridge_backendInit(JNIEnv * env, jclass) {
    std::call_once(g_backend_once, [] {
        llama_log_set(android_log_callback, nullptr);
        llama_backend_init();
        g_backend_ready.store(true);
    });
    return env->NewStringUTF(llama_version());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localai_agent_inference_LlamaBridge_backendFree(JNIEnv *, jclass) {
    // Intentionally a no-op: the backend lives for the process lifetime.
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_localai_agent_inference_LlamaBridge_loadModel(
        JNIEnv * env,
        jclass,
        jstring j_path,
        jint n_ctx,
        jint n_threads,
        jint n_gpu_layers,
        jint n_batch) {
    if (!g_backend_ready.load()) {
        LOGE("loadModel called before backendInit");
        return 0;
    }

    const std::string path = to_utf8(env, j_path);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    mparams.check_tensors = false;

    LOGI("loading model %s (n_ctx=%d threads=%d gpu_layers=%d)",
         path.c_str(), n_ctx, n_threads, n_gpu_layers);

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed for %s", path.c_str());
        return 0;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (vocab == nullptr) {
        llama_model_free(model);
        return 0;
    }

    auto * session = new Session();
    session->model = model;
    session->vocab = vocab;
    session->n_ctx = n_ctx > 0 ? n_ctx : 2048;
    session->n_threads = n_threads > 0 ? n_threads : 4;
    session->n_batch = n_batch > 0 ? n_batch : 512;

    {
        std::lock_guard<std::mutex> lock(g_registry_mu);
        g_sessions.insert(session);
    }
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localai_agent_inference_LlamaBridge_freeModel(JNIEnv *, jclass, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    {
        std::lock_guard<std::mutex> lock(g_registry_mu);
        if (g_sessions.erase(session) == 0) return;
    }
    {
        std::lock_guard<std::mutex> lock(session->mu);
        if (session->model != nullptr) {
            llama_model_free(session->model);
            session->model = nullptr;
        }
    }
    // The Session object itself is intentionally not deleted: an in-flight
    // generate()/cancel() may still hold its pointer after the registry erase.
    // Leaking ~100 bytes per unload is cheaper than a use-after-free.
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localai_agent_inference_LlamaBridge_cancel(JNIEnv *, jclass, jlong handle) {
    if (auto * session = lookup(handle)) {
        session->cancel.store(true);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localai_agent_inference_LlamaBridge_generate(
        JNIEnv * env,
        jclass,
        jlong handle,
        jstring j_prompt,
        jint max_tokens,
        jfloat temperature,
        jfloat top_p,
        jint top_k,
        jfloat repeat_penalty,
        jobjectArray j_stops,
        jobject sink) {
    Session * session = lookup(handle);
    if (session == nullptr) return -2;

    std::lock_guard<std::mutex> session_lock(session->mu);
    if (session->model == nullptr) return -2;
    session->cancel.store(false);

    const std::string prompt = to_utf8(env, j_prompt);
    if (prompt.empty()) return -2;

    std::vector<std::string> stops;
    if (j_stops != nullptr) {
        const jsize n = env->GetArrayLength(j_stops);
        for (jsize i = 0; i < n; ++i) {
            auto * item = static_cast<jstring>(env->GetObjectArrayElement(j_stops, i));
            if (item != nullptr) {
                stops.push_back(to_utf8(env, item));
                env->DeleteLocalRef(item);
            }
        }
    }
    size_t max_stop_len = 0;
    for (const auto & s : stops) max_stop_len = std::max(max_stop_len, s.size());

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(session->n_ctx);
    cparams.n_batch = static_cast<uint32_t>(session->n_batch);
    cparams.n_ubatch = static_cast<uint32_t>(session->n_batch);
    cparams.n_threads = session->n_threads;
    cparams.n_threads_batch = session->n_threads;
    cparams.no_perf = true;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    llama_context * ctx = llama_init_from_model(session->model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed (n_ctx=%d)", session->n_ctx);
        return -2;
    }

    // Tokenize the prompt (special tokens in the template must be parsed as tokens).
    const std::string & text = prompt;
    int32_t n_tokens = llama_tokenize(
            session->vocab, text.data(), static_cast<int32_t>(text.size()),
            nullptr, 0, /*add_special=*/false, /*parse_special=*/true);
    if (n_tokens < 0) n_tokens = -n_tokens;
    if (n_tokens <= 0) {
        llama_free(ctx);
        return -2;
    }

    std::vector<llama_token> tokens(static_cast<size_t>(n_tokens));
    const int32_t filled = llama_tokenize(
            session->vocab, text.data(), static_cast<int32_t>(text.size()),
            tokens.data(), n_tokens, false, true);
    if (filled <= 0) {
        llama_free(ctx);
        return -2;
    }
    tokens.resize(static_cast<size_t>(filled));

    const int32_t budget = session->n_ctx - 8;
    if (static_cast<int32_t>(tokens.size()) > budget) {
        LOGW("prompt too long: %d tokens, context %d", static_cast<int>(tokens.size()), session->n_ctx);
        llama_free(ctx);
        return -3;
    }

    if (!decode_tokens(ctx, tokens, 0, session->n_batch, /*logits_on_last=*/true)) {
        llama_free(ctx);
        return -2;
    }

    llama_sampler * sampler = build_sampler(
            session->vocab,
            temperature,
            top_p,
            top_k,
            repeat_penalty > 0.0f ? repeat_penalty : 1.0f);

    jclass sink_class = sink != nullptr ? env->GetObjectClass(sink) : nullptr;
    jmethodID on_token = sink_class != nullptr
            ? env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V")
            : nullptr;

    std::string generated;
    std::string pending;
    int32_t n_past = static_cast<int32_t>(tokens.size());
    int32_t produced = 0;
    int32_t result = 0;

    auto emit = [&](size_t up_to) {
        if (up_to == 0 || on_token == nullptr || sink == nullptr) return false;
        const std::string chunk = pending.substr(0, up_to);
        pending.erase(0, up_to);
        jstring js = from_utf8(env, chunk);
        env->CallVoidMethod(sink, on_token, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result = -1;
            session->cancel.store(true);
            return false;
        }
        return true;
    };

    for (; produced < max_tokens; ++produced) {
        if (session->cancel.load()) { result = -1; break; }

        const llama_token id = llama_sampler_sample(sampler, ctx, -1);
        if (id < 0) { result = -2; break; }
        if (llama_vocab_is_eog(session->vocab, id)) break;

        llama_sampler_accept(sampler, id);

        char stack_buf[256];
        int32_t n_piece = llama_token_to_piece(
                session->vocab, id, stack_buf, sizeof(stack_buf), 0, /*special=*/false);
        if (n_piece > 0) {
            pending.append(stack_buf, static_cast<size_t>(n_piece));
            generated.append(stack_buf, static_cast<size_t>(n_piece));
        } else if (n_piece < 0) {
            std::vector<char> heap(static_cast<size_t>(-n_piece));
            n_piece = llama_token_to_piece(
                    session->vocab, id, heap.data(), static_cast<int32_t>(heap.size()), 0, false);
            if (n_piece > 0) {
                pending.append(heap.data(), static_cast<size_t>(n_piece));
                generated.append(heap.data(), static_cast<size_t>(n_piece));
            }
        }

        // Stop sequences are matched against the full generated text.
        size_t stop_at = std::string::npos;
        for (const auto & s : stops) {
            if (s.empty()) continue;
            const size_t pos = generated.find(s);
            if (pos != std::string::npos) {
                stop_at = std::min(stop_at, pos);
            }
        }
        if (stop_at != std::string::npos) {
            // The stop marker is detected after it has been generated, but up to
            // (max_stop_len - 1) bytes immediately before it are still in `pending`
            // because of the holdback. Flush that prefix before discarding the marker;
            // otherwise the last few characters of every answer (or the closing ```
            // of a tool call) are silently lost.
            const size_t emitted = generated.size() - pending.size();
            if (stop_at > emitted) {
                size_t tail = std::min(stop_at - emitted, pending.size());
                tail = utf8_safe_len(pending, tail);
                if (tail > 0 && !emit(tail)) break;
            }
            generated.resize(stop_at);
            pending.clear();
            break;
        }

        // Hold back a few bytes so a stop sequence is not streamed to the UI.
        size_t limit = pending.size();
        if (max_stop_len > 1) {
            const size_t hold = max_stop_len - 1;
            limit = limit > hold ? limit - hold : 0;
        }
        const size_t safe = utf8_safe_len(pending, limit);
        if (safe > 0 && !emit(safe)) break;

        llama_batch batch = llama_batch_init(1, 0, 1);
        if (batch.token == nullptr) { result = -2; break; }
        batch.token[0] = id;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;
        const int32_t rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) { result = -2; break; }

        // Respect the context budget even if the caller asked for more.
        if (n_past >= session->n_ctx - 1) break;
    }

    // Flush whatever is still on a UTF-8 boundary.
    if (result == 0 && !pending.empty()) {
        const size_t safe = utf8_safe_len(pending, pending.size());
        if (safe > 0) emit(safe);
    }

    if (sink_class != nullptr) env->DeleteLocalRef(sink_class);
    llama_sampler_free(sampler);
    llama_free(ctx);

    if (result != 0) return result;
    return produced > 0 ? produced : 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localai_agent_inference_LlamaBridge_tokenCount(
        JNIEnv * env, jclass, jlong handle, jstring j_text) {
    Session * session = lookup(handle);
    if (session == nullptr) return -1;

    const std::string text = to_utf8(env, j_text);
    int32_t n = llama_tokenize(
            session->vocab, text.data(), static_cast<int32_t>(text.size()),
            nullptr, 0, false, true);
    return n < 0 ? -n : n;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localai_agent_inference_LlamaBridge_modelInfo(JNIEnv * env, jclass, jlong handle) {
    Session * session = lookup(handle);
    if (session == nullptr) return env->NewStringUTF("{}");

    char desc[512] = {0};
    llama_model_desc(session->model, desc, sizeof(desc));

    char json[4096];
    std::snprintf(
            json, sizeof(json),
            "{\"desc\":\"%s\",\"params\":%" PRIu64 ",\"sizeBytes\":%" PRIu64
            ",\"nCtxTrain\":%d,\"nEmbd\":%d,\"nHead\":%d,\"nHeadKv\":%d}",
            json_escape(desc).c_str(),
            static_cast<uint64_t>(llama_model_n_params(session->model)),
            static_cast<uint64_t>(llama_model_size(session->model)),
            llama_model_n_ctx_train(session->model),
            llama_model_n_embd(session->model),
            llama_model_n_head(session->model),
            llama_model_n_head_kv(session->model));
    return from_utf8(env, json);
}
