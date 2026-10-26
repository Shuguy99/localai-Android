// JNI bridge between Kotlin (dev.localai.agent.inference.LlamaBridge) and llama.cpp.
//
// Design notes:
//  * Weights are loaded once per model (mmap) and kept in a Session.
//  * The llama_context is persistent and shared by all generations of a session.
//    A token-level prefix cache remembers which tokens already live in the KV
//    cache; on the next generate() the longest common prefix with the new prompt
//    is reused and only the tail is decoded. In the agent loop every step shares
//    the system prompt + conversation history, so step N typically re-decodes a
//    few hundred tokens instead of the whole multi-thousand-token prompt.
//  * Fallbacks keep the cache honest: if the KV cannot be trimmed (SWA-style
//    memories may refuse a partial removal) or a decode fails, the cache is
//    cleared and the next request re-decodes from scratch — correctness first.
//  * When the new prompt is fully covered by the cache, the last prompt token is
//    re-decoded so the logits buffer is guaranteed to match the prompt tail.
//  * Sessions are owned by a shared_ptr registry: freeModel() drops the registry
//    reference, an in-flight generate() keeps the Session alive until it returns.
//    No intentional leaks, no use-after-free.
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
#include <memory>
#include <mutex>
#include <random>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama.h"

#define TAG "localai-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// How many trailing tokens are fed to the penalties sampler when a generation
// starts (mirrors the upstream server, which seeds its "prev" ring from the
// prompt tail so repeat penalties survive across cached prefixes).
constexpr int32_t kPenaltyWindow = 64;

struct Session {
    llama_model * model = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 2048;
    int n_batch = 512;
    int n_threads = 4;
    // q8_0 KV cache: halves the attention memory bandwidth, which dominates
    // decode time on phones. Requires flash attention for a quantized V cache;
    // ensure_context() falls back to F16 when the backend refuses the combo.
    bool kv_q8 = true;

    // Persistent context and the tokens currently resident in its KV cache for
    // sequence 0. Both are guarded by `mu`; `cached.size()` is always the number
    // of decoded positions (0-based, contiguous) in the cache, and the logits
    // buffer always corresponds to the last cached token after generate() runs.
    llama_context * ctx = nullptr;
    std::vector<llama_token> cached;

    std::atomic<bool> cancel{false};
    std::mutex mu;
};

std::mutex g_registry_mu;
std::unordered_map<Session *, std::shared_ptr<Session>> g_sessions;

std::shared_ptr<Session> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(g_registry_mu);
    const auto it = g_sessions.find(reinterpret_cast<Session *>(handle));
    return it != g_sessions.end() ? it->second : nullptr;
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
    // NewString(data, 0) is not required to accept a null pointer; give it a real
    // empty string instead.
    if (utf16.empty()) return env->NewStringUTF("");
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
            llama_sampler_init_penalties(n_vocab, kPenaltyWindow, repeat_penalty, 0.0f, 0.0f));

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

// Frees the persistent context and invalidates the token cache. Caller holds mu.
void destroy_context(Session * s) {
    if (s->ctx != nullptr) {
        llama_free(s->ctx);
        s->ctx = nullptr;
    }
    s->cached.clear();
}

// Creates the persistent context lazily on first use so loadModel stays fast.
// Caller holds mu.
bool ensure_context(Session * s) {
    if (s->ctx != nullptr) return true;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(s->n_ctx);
    cparams.n_batch = static_cast<uint32_t>(s->n_batch);
    cparams.n_ubatch = static_cast<uint32_t>(s->n_batch);
    cparams.n_threads = s->n_threads;
    cparams.n_threads_batch = s->n_threads;
    cparams.no_perf = true;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    // First attempt: q8_0 KV when requested. Second attempt: stock F16 KV. A
    // backend that cannot serve quantized V (no flash attention) fails the
    // first attempt without side effects, so the retry keeps the session usable.
    for (int attempt = 0; attempt < 2; ++attempt) {
        const bool try_q8 = (attempt == 0 && s->kv_q8);
        cparams.type_k = try_q8 ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
        cparams.type_v = try_q8 ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
        s->ctx = llama_init_from_model(s->model, cparams);
        if (s->ctx != nullptr) {
            s->cached.clear();
            return true;
        }
        if (!try_q8) break;
        LOGW("q8_0 KV context rejected; retrying with F16 KV");
    }
    LOGE("llama_init_from_model failed (n_ctx=%d)", s->n_ctx);
    return false;
}

// Length of the longest common prefix of the cached KV tokens and the new prompt.
size_t common_prefix_len(const std::vector<llama_token> & a, const std::vector<llama_token> & b) {
    const size_t max = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < max && a[i] == b[i]) ++i;
    return i;
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
        jint n_batch,
        jboolean kv_q8) {
    if (!g_backend_ready.load()) {
        LOGE("loadModel called before backendInit");
        return 0;
    }

    const std::string path = to_utf8(env, j_path);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    mparams.check_tensors = false;

    LOGI("loading model %s (n_ctx=%d threads=%d gpu_layers=%d kv_q8=%d)",
         path.c_str(), n_ctx, n_threads, n_gpu_layers, static_cast<int>(kv_q8));

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

    auto session = std::make_shared<Session>();
    session->model = model;
    session->vocab = vocab;
    session->n_ctx = n_ctx > 0 ? n_ctx : 2048;
    session->n_threads = n_threads > 0 ? n_threads : 4;
    session->n_batch = n_batch > 0 ? n_batch : 512;
    session->kv_q8 = (kv_q8 != JNI_FALSE);

    {
        std::lock_guard<std::mutex> lock(g_registry_mu);
        g_sessions.emplace(session.get(), session);
    }
    return reinterpret_cast<jlong>(session.get());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localai_agent_inference_LlamaBridge_freeModel(JNIEnv *, jclass, jlong handle) {
    std::shared_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lock(g_registry_mu);
        const auto it = g_sessions.find(reinterpret_cast<Session *>(handle));
        if (it == g_sessions.end()) return;
        session = it->second;
        g_sessions.erase(it);
    }
    // Holding the shared_ptr keeps the Session alive even if a generate() call
    // raced us to lookup(); the mutex below serialises the teardown itself.
    std::lock_guard<std::mutex> lock(session->mu);
    destroy_context(session.get());
    if (session->model != nullptr) {
        llama_model_free(session->model);
        session->model = nullptr;
    }
    // The Session object is released when the last shared_ptr (ours, or one held
    // by an in-flight generate/tokenCount/modelInfo) goes out of scope.
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localai_agent_inference_LlamaBridge_cancel(JNIEnv *, jclass, jlong handle) {
    if (auto session = lookup(handle)) {
        session->cancel.store(true);
    }
}

// Drops the KV prefix cache (and with it every decoded position). Used when the
// conversation is reset so the next request starts from a clean, compact cache.
extern "C" JNIEXPORT void JNICALL
Java_dev_localai_agent_inference_LlamaBridge_resetContext(JNIEnv *, jclass, jlong handle) {
    if (auto session = lookup(handle)) {
        std::lock_guard<std::mutex> lock(session->mu);
        if (session->ctx != nullptr) {
            llama_memory_clear(llama_get_memory(session->ctx), /*data=*/true);
        }
        session->cached.clear();
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
    std::shared_ptr<Session> session = lookup(handle);
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

    if (!ensure_context(session.get())) return -2;
    llama_context * ctx = session->ctx;
    llama_memory_t mem = llama_get_memory(ctx);

    // Tokenize the prompt (special tokens in the template must be parsed as tokens).
    const std::string & text = prompt;
    int32_t n_tokens = llama_tokenize(
            session->vocab, text.data(), static_cast<int32_t>(text.size()),
            nullptr, 0, /*add_special=*/false, /*parse_special=*/true);
    if (n_tokens < 0) n_tokens = -n_tokens;
    if (n_tokens <= 0) return -2;

    std::vector<llama_token> tokens(static_cast<size_t>(n_tokens));
    const int32_t filled = llama_tokenize(
            session->vocab, text.data(), static_cast<int32_t>(text.size()),
            tokens.data(), n_tokens, false, true);
    if (filled <= 0) return -2;
    tokens.resize(static_cast<size_t>(filled));

    const int32_t budget = session->n_ctx - 8;
    if (static_cast<int32_t>(tokens.size()) > budget) {
        LOGW("prompt too long: %d tokens, context %d", static_cast<int>(tokens.size()), session->n_ctx);
        return -3;
    }

    // ---- Prefix cache reconciliation ------------------------------------
    // Trim the KV cache and `cached` down to the common prefix with the new
    // prompt, then decode only the tail. One prompt token is always re-decoded
    // so the logits buffer is guaranteed to match the end of the prompt.
    int32_t lcp = static_cast<int32_t>(common_prefix_len(session->cached, tokens));
    if (lcp == static_cast<int32_t>(tokens.size())) --lcp;
    if (lcp < static_cast<int32_t>(session->cached.size())) {
        const bool trimmed = llama_memory_seq_rm(mem, /*seq_id=*/0, /*p0=*/lcp, /*p1=*/-1);
        if (!trimmed) {
            // SWA-style memories can refuse partial removal; a full reset is
            // always correct, just slower.
            llama_memory_clear(mem, /*data=*/true);
            lcp = 0;
        }
    }
    if (lcp == 0) {
        llama_memory_clear(mem, /*data=*/true);
    }
    session->cached.resize(static_cast<size_t>(lcp));

    if (lcp < static_cast<int32_t>(tokens.size())) {
        std::vector<llama_token> tail(tokens.begin() + lcp, tokens.end());
        if (!decode_tokens(ctx, tail, lcp, session->n_batch, /*logits_on_last=*/true)) {
            // The KV state is undefined after a failed decode; force the next
            // request to start from a clean cache.
            llama_memory_clear(mem, /*data=*/true);
            session->cached.clear();
            return -2;
        }
    }
    session->cached.insert(session->cached.end(), tokens.begin() + lcp, tokens.end());

    int32_t n_past = static_cast<int32_t>(session->cached.size());

    llama_sampler * sampler = build_sampler(
            session->vocab,
            temperature,
            top_p,
            top_k,
            repeat_penalty > 0.0f ? repeat_penalty : 1.0f);

    // Seed the penalties window with the prompt tail (cached or freshly decoded)
    // so repeat penalties behave the same as in the upstream server.
    const int32_t seed_n = std::min<int32_t>(kPenaltyWindow, n_past);
    for (int32_t i = n_past - seed_n; i < n_past; ++i) {
        llama_sampler_accept(sampler, session->cached[i]);
    }

    jclass sink_class = sink != nullptr ? env->GetObjectClass(sink) : nullptr;
    jmethodID on_token = sink_class != nullptr
            ? env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V")
            : nullptr;

    std::string generated;
    std::string pending;
    int32_t produced = 0;
    int32_t result = 0;

    auto emit = [&](size_t up_to) {
        if (up_to == 0) return false;
        // No consumer registered: dropping the chunk is success, not failure —
        // returning false here would abort generation after the first token.
        if (on_token == nullptr || sink == nullptr) return true;
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

    // Forward-only start position for stop-sequence scans. Everything before it
    // was already verified to not start a complete stop sequence, which keeps
    // the per-token scan O(window) instead of O(generated).
    size_t stop_search_from = 0;

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

        if (max_stop_len > 0) {
            // A stop sequence that is still incomplete can only begin in the
            // last (max_stop_len - 1) bytes, so the scan start only moves forward.
            // A sequence that JUST completed starts at size-L >= size-max_stop_len,
            // so the scan window must include size-max_stop_len; the previous
            // "size - max_stop_len + 1" skipped every marker whose length equalled
            // max_stop_len ("[INST]", "<|im_start|>", "<|end_of_text|>"), letting
            // it leak into the output and never stop the generation.
            if (generated.size() >= max_stop_len) {
                stop_search_from = std::max(stop_search_from, generated.size() - max_stop_len);
            }
            size_t stop_at = std::string::npos;
            for (const auto & s : stops) {
                if (s.empty()) continue;
                const size_t pos = generated.find(s, stop_search_from);
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
        batch.pos[0] = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;
        const int32_t rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            llama_memory_clear(mem, /*data=*/true);
            session->cached.clear();
            result = -2;
            break;
        }
        session->cached.push_back(id);
        n_past = static_cast<int32_t>(session->cached.size());

        // Respect the context budget even if the caller asked for more.
        if (n_past >= session->n_ctx - 1) break;
    }

    // Flush whatever is still on a UTF-8 boundary. This includes the cancelled
    // case: the user asked to stop, so give them the complete partial text
    // instead of silently dropping the holdback window.
    if (!pending.empty() && (result == 0 || result == -1)) {
        const size_t safe = utf8_safe_len(pending, pending.size());
        if (safe > 0) emit(safe);
    }

    if (sink_class != nullptr) env->DeleteLocalRef(sink_class);
    llama_sampler_free(sampler);

    if (result != 0) return result;
    return produced;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localai_agent_inference_LlamaBridge_tokenCount(
        JNIEnv * env, jclass, jlong handle, jstring j_text) {
    std::shared_ptr<Session> session = lookup(handle);
    if (session == nullptr) return -1;

    // modelInfo/tokenCount used to read the model pointer without a lock, which
    // raced freeModel(). The session mutex is held for the whole generation, so
    // this only ever blocks behind an in-flight generation.
    std::lock_guard<std::mutex> lock(session->mu);
    if (session->vocab == nullptr) return -1;

    const std::string text = to_utf8(env, j_text);
    int32_t n = llama_tokenize(
            session->vocab, text.data(), static_cast<int32_t>(text.size()),
            nullptr, 0, false, true);
    return n < 0 ? -n : n;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localai_agent_inference_LlamaBridge_modelInfo(JNIEnv * env, jclass, jlong handle) {
    std::shared_ptr<Session> session = lookup(handle);
    if (session == nullptr) return env->NewStringUTF("{}");

    std::lock_guard<std::mutex> lock(session->mu);
    if (session->model == nullptr) return env->NewStringUTF("{}");

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
