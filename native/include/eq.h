#pragma once
#include <stdbool.h>
#include <stdint.h>

// Stable C boundary. Paired with rust/crates/ffi-android/src/lib.rs.
// Stop/close the Oboe stream before destroying a handle. One render thread only.
#ifdef __cplusplus
extern "C" {
#endif
typedef struct EqHandle EqHandle;
typedef struct EqEffect EqEffect;
typedef struct EqStats {
    uint64_t rendered_frames;
    uint64_t underrun_callbacks;
    float peak;
    uint32_t playing;
    uint32_t finished;
    double duration_seconds;
    double position_seconds;
} EqStats;
EqHandle *eq_create(uint32_t sample_rate);
void eq_destroy(EqHandle *handle);
void eq_process(EqHandle *handle, float *output, uint32_t frames);
bool eq_configure(EqHandle *handle, const float *values, uint32_t count);
bool eq_load(EqHandle *handle, const char *path);
bool eq_load_at(EqHandle *handle, const char *path, double seconds);
void eq_set_playing(EqHandle *handle, bool playing);
void eq_stats(EqHandle *handle, EqStats *output);
uint32_t eq_error(EqHandle *handle, char *buffer, uint32_t capacity);
// prepare/destroy are worker-only. install consumes effect only on success.
EqEffect *eq_effect_prepare(uint32_t sample_rate, const char *ir_path, const char *sofa_path,
                          float azimuth, float wet, uint32_t quality, char *error, uint32_t capacity);
bool eq_effect_install(EqHandle *handle, EqEffect *effect);
void eq_effect_destroy(EqEffect *effect);
uint32_t eq_effect_latency(const EqEffect *effect);
bool eq_autoeq(const char *csv, uint32_t bands, uint32_t sample_rate,
              char *output, uint32_t capacity, char *error, uint32_t error_capacity);
#ifdef __cplusplus
}
#endif

