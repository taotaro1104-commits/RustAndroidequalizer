#include <jni.h>
#include <oboe/Oboe.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <time.h>
#include <limits>
#include <unordered_map>
#include <cmath>
#include "eq.h"

namespace {
constexpr size_t kConfigSize = 104;
constexpr size_t kBins = 128;
int64_t monotonicNanos() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return int64_t(t.tv_sec) * 1000000000LL + t.tv_nsec;
}

// Stream owns the callbacks through shared_ptr. The Rust handle outlives callbacks.
class Callback final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    EqHandle *handle = nullptr;
    std::atomic<bool> disconnected{false};
    std::atomic<int32_t> frames{0};
    std::array<std::atomic<uint64_t>, kBins> histogram{};
    std::array<uint64_t, kBins> metricsBins{}; // JNI/control-only snapshot.
    ~Callback() override { if (handle) eq_destroy(handle); }
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *data, int32_t numFrames) override {
        const auto begin = monotonicNanos();
        eq_process(handle, static_cast<float *>(data), static_cast<uint32_t>(numFrames));
        const auto elapsed = monotonicNanos() - begin;
        const size_t bin = std::min<size_t>(kBins - 1, std::max<int64_t>(0, elapsed) / 100000);
        histogram[bin].fetch_add(1, std::memory_order_relaxed);
        frames.store(numFrames, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }
    void onErrorAfterClose(oboe::AudioStream *, oboe::Result) override {
        disconnected.store(true, std::memory_order_release);
    }
    float percentile(float fraction) const {
        uint64_t total = 0;
        for (const auto bin : metricsBins) total += bin;
        if (!total) return 0;
        const uint64_t rank = std::max<uint64_t>(1, static_cast<uint64_t>(total * fraction + 0.999));
        uint64_t cumulative = 0;
        for (size_t i = 0; i < kBins; ++i) {
            cumulative += metricsBins[i];
            if (cumulative >= rank) return i == kBins-1 ? std::numeric_limits<float>::infinity() : float(i + 1) * 100.0f;
        }
        return kBins * 100.0f;
    }
    void snapshot() { for (size_t i = 0; i < kBins; ++i) metricsBins[i] = histogram[i].exchange(0, std::memory_order_relaxed); }
};

class Session {
public:
    std::string sourcePath;
    bool demo = true;
    std::shared_ptr<oboe::AudioStream> stream;
    std::shared_ptr<Callback> callback;
    int32_t lastXruns = 0;
    ~Session() { close(); }
    void close() {
        if (callback && callback->handle) eq_set_playing(callback->handle, false);
        if (stream) { stream->requestStop(); stream->close(); stream.reset(); }
        callback.reset();
    }
    std::string load(const char *path, const std::array<float, kConfigSize> &config, bool haveConfig, double seconds = 0.0) {
        close();
        sourcePath = path ? path : ""; demo = path == nullptr;
        callback = std::make_shared<Callback>();
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setAudioApi(oboe::AudioApi::AAudio)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(callback))
            ->setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(callback));
        auto result = builder.openStream(stream);
        if (result != oboe::Result::OK) {
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream);
        }
        if (result != oboe::Result::OK) return oboe::convertToText(result);
        callback->handle = eq_create(stream->getSampleRate());
        if (haveConfig) eq_configure(callback->handle, config.data(), config.size());
        if (!eq_load_at(callback->handle, path, seconds)) {
            std::array<char, 1024> message{};
            eq_error(callback->handle, message.data(), message.size());
            return message.data();
        }
        stream->setBufferSizeInFrames(stream->getFramesPerBurst() * 2);
        lastXruns = 0;
        // The UI grants audio focus before explicitly starting playback.
        eq_set_playing(callback->handle, false);
        result = stream->requestStart();
        if (result != oboe::Result::OK) return oboe::convertToText(result);
        return {};
    }
};

std::mutex controlMutex; // Only JNI/control threads acquire this; never the callback.
struct Bridge {
    std::unique_ptr<Session> session;
    std::array<float, kConfigSize> cachedConfig{};
    bool haveConfig = false;
};
std::unordered_map<jlong, Bridge> bridges;
jlong ownerId(JNIEnv *env, jobject object) {
    jclass type = env->GetObjectClass(object);
    jfieldID field = env->GetFieldID(type, "ownerId", "J");
    env->DeleteLocalRef(type);
    return field ? env->GetLongField(object, field) : 0;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_rusteq_audio_NativeEngine_prepareEffect(JNIEnv *env, jobject, jint sampleRate, jstring ir, jstring sofa,
                                                jfloat azimuth, jfloat wet, jint quality) {
    // No session or control mutex here: preparing SOFA/FFT must not block pause/close.
    const char *irText = ir ? env->GetStringUTFChars(ir, nullptr) : nullptr;
    if (ir && !irText) return 0;
    const char *sofaText = sofa ? env->GetStringUTFChars(sofa, nullptr) : nullptr;
    if (sofa && !sofaText) { if (irText) env->ReleaseStringUTFChars(ir, irText); return 0; }
    std::array<char, 1024> message{};
    auto effect = eq_effect_prepare(sampleRate, irText, sofaText, azimuth, wet, quality, message.data(), message.size());
    if (irText) env->ReleaseStringUTFChars(ir, irText);
    if (sofaText) env->ReleaseStringUTFChars(sofa, sofaText);
    if (!effect) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        if (error) { env->ThrowNew(error, message.data()); env->DeleteLocalRef(error); }
    }
    return reinterpret_cast<jlong>(effect);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_rusteq_audio_NativeEngine_optimize(JNIEnv *env, jobject, jstring csv, jint bands, jint sampleRate) {
    if (!csv) return nullptr;
    const char *text = env->GetStringUTFChars(csv, nullptr);
    if (!text) return nullptr;
    std::array<char, 8192> output{};
    std::array<char, 1024> error{};
    const bool success = eq_autoeq(text, bands, sampleRate, output.data(), output.size(), error.data(), error.size());
    env->ReleaseStringUTFChars(csv, text);
    if (!success) {
        jclass type = env->FindClass("java/lang/IllegalArgumentException");
        if (type) { env->ThrowNew(type, error.data()); env->DeleteLocalRef(type); }
        return nullptr;
    }
    return env->NewStringUTF(output.data());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rusteq_audio_NativeEngine_installEffect(JNIEnv *env, jobject object, jlong prepared) {
    std::lock_guard<std::mutex> guard(controlMutex);
    auto &session = bridges[ownerId(env, object)].session;
    if (!session || !session->callback || session->callback->disconnected.load(std::memory_order_acquire)) return JNI_FALSE;
    return eq_effect_install(session->callback->handle, reinterpret_cast<EqEffect *>(prepared)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rusteq_audio_NativeEngine_releaseEffect(JNIEnv *, jobject, jlong prepared) {
    eq_effect_destroy(reinterpret_cast<EqEffect *>(prepared));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_rusteq_audio_NativeEngine_effectLatency(JNIEnv *, jobject, jlong prepared) {
    return static_cast<jint>(eq_effect_latency(reinterpret_cast<EqEffect *>(prepared)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_rusteq_audio_NativeEngine_load(JNIEnv *env, jobject object, jstring path) {
    std::lock_guard<std::mutex> guard(controlMutex);
    auto &bridge = bridges[ownerId(env, object)]; auto &session = bridge.session;
    session = std::make_unique<Session>();
    const char *text = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    if (path && !text) return nullptr; // Pending JNI exception.
    std::string error = session->load(text, bridge.cachedConfig, bridge.haveConfig);
    if (text) env->ReleaseStringUTFChars(path, text);
    if (!error.empty()) session.reset();
    // Error text from codecs is ASCII/UTF-8; avoid passing arbitrary invalid UTF-8 to JNI.
    return env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rusteq_audio_NativeEngine_configure(JNIEnv *env, jobject object, jfloatArray values) {
    if (!values) return JNI_FALSE;
    const jsize count = env->GetArrayLength(values);
    if (count != 102 && count != static_cast<jsize>(kConfigSize)) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(controlMutex);
    auto &bridge = bridges[ownerId(env, object)]; auto &session = bridge.session;
    bridge.cachedConfig.fill(0.0f);
    bridge.cachedConfig[103] = 110.0f;
    env->GetFloatArrayRegion(values, 0, count, bridge.cachedConfig.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    bridge.haveConfig = true;
    if (!session || !session->callback || !session->callback->handle) return JNI_TRUE;
    return eq_configure(session->callback->handle, bridge.cachedConfig.data(), kConfigSize) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_rusteq_audio_NativeEngine_seekTo(JNIEnv *env, jobject object, jdouble seconds) {
    std::lock_guard<std::mutex> guard(controlMutex);
    auto &bridge = bridges[ownerId(env, object)];
    if (!bridge.session || !std::isfinite(seconds) || seconds < 0) return env->NewStringUTF("Invalid seek position or no source loaded");
    const std::string path = bridge.session->sourcePath;
    const bool demo = bridge.session->demo;
    std::string error = bridge.session->load(demo ? nullptr : path.c_str(), bridge.cachedConfig, bridge.haveConfig, seconds);
    if (!error.empty()) bridge.session.reset();
    return env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rusteq_audio_NativeEngine_setPlaying(JNIEnv *env, jobject object, jboolean playing) {
    std::lock_guard<std::mutex> guard(controlMutex);
    auto &session = bridges[ownerId(env, object)].session;
    if (session && session->callback && !session->callback->disconnected.load(std::memory_order_acquire))
        eq_set_playing(session->callback->handle, playing == JNI_TRUE);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_rusteq_audio_NativeEngine_metrics(JNIEnv *env, jobject object) {
    std::lock_guard<std::mutex> guard(controlMutex);
    auto &session = bridges[ownerId(env, object)].session;
    std::array<float, 14> v{};
    if (session && session->stream && session->callback) {
        auto &s = *session;
        auto &c = *s.callback;
        c.snapshot();
        EqStats stats{};
        eq_stats(c.handle, &stats);
        auto xruns = s.stream->getXRunCount();
        const int32_t count = xruns ? xruns.value() : 0;
        // Runtime buffer adjustment stays on the control thread.
        if (count > s.lastXruns && !c.disconnected.load(std::memory_order_acquire)) {
            s.stream->setBufferSizeInFrames(std::min(s.stream->getBufferCapacityInFrames(), s.stream->getBufferSizeInFrames() + s.stream->getFramesPerBurst()));
        }
        s.lastXruns = count;
        v = {float(s.stream->getSampleRate()), float(s.stream->getFramesPerBurst()), float(s.stream->getBufferSizeInFrames()), float(count),
             float(stats.underrun_callbacks), stats.peak, float(stats.position_seconds),
             float(stats.playing && !stats.finished && !c.disconnected.load(std::memory_order_acquire)), float(stats.finished),
             float(c.disconnected.load(std::memory_order_acquire)), c.percentile(0.95f), c.percentile(0.99f), float(c.frames.load(std::memory_order_relaxed)), float(stats.duration_seconds)};
    }
    auto result = env->NewFloatArray(v.size());
    if (result) env->SetFloatArrayRegion(result, 0, v.size(), v.data());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_rusteq_audio_NativeEngine_error(JNIEnv *env, jobject object) {
    std::lock_guard<std::mutex> guard(controlMutex);
    auto &session = bridges[ownerId(env, object)].session;
    std::array<char, 1024> message{};
    if (session && session->callback) eq_error(session->callback->handle, message.data(), message.size());
    return env->NewStringUTF(message.data());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rusteq_audio_NativeEngine_close(JNIEnv *env, jobject object) {
    std::lock_guard<std::mutex> guard(controlMutex);
    bridges.erase(ownerId(env, object));
}
