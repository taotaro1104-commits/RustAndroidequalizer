pub const MAX_BANDS: usize = 31;

#[derive(Clone, Copy, Debug)]
pub struct Band {
    pub frequency: f32,
    pub gain_db: f32,
    pub q: f32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum SpatialMode {
    Off,
    Wide,
    Crossfeed,
}

#[derive(Clone, Copy, Debug)]
pub struct Config {
    pub bands: [Band; MAX_BANDS],
    pub band_count: usize,
    pub preamp_db: f32,
    pub bypass: bool,
    pub compressor: bool,
    pub threshold_db: f32,
    pub ratio: f32,
    pub spatial: SpatialMode,
    pub width: f32,
    pub crossfeed: f32,
    pub virtual_bass_amount: f32,
    pub virtual_bass_hz: f32,
    /// Echo wet level 0..1 (0 = off), delay time in ms, feedback 0..0.85.
    pub echo_mix: f32,
    pub echo_time_ms: f32,
    pub echo_feedback: f32,
    /// Room reverb wet level 0..1 (0 = off), RT60 in seconds and damping 0..1.
    pub reverb_mix: f32,
    pub reverb_decay_s: f32,
    pub reverb_damping: f32,
    /// Harmonic exciter amount 0..1 (0 = off) and the frequency above which it works.
    pub exciter_amount: f32,
    pub exciter_hz: f32,
    /// Dynamic low-band EQ: maximum cut in dB (0 = off), band edge, threshold and ratio.
    pub dyn_eq_range_db: f32,
    pub dyn_eq_hz: f32,
    pub dyn_eq_threshold_db: f32,
    pub dyn_eq_ratio: f32,
    /// Look-ahead true-peak limiter (otherwise the zero-latency sample-peak limiter).
    pub true_peak_limiter: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bands: std::array::from_fn(|i| Band {
                frequency: 20.0 * 1000.0_f32.powf(i as f32 / 30.0),
                gain_db: 0.0,
                q: 4.3,
            }),
            band_count: 31,
            preamp_db: -6.0,
            bypass: false,
            compressor: false,
            threshold_db: -18.0,
            ratio: 3.0,
            spatial: SpatialMode::Off,
            width: 1.3,
            crossfeed: 0.25,
            virtual_bass_amount: 0.0,
            virtual_bass_hz: 110.0,
            echo_mix: 0.0,
            echo_time_ms: 300.0,
            echo_feedback: 0.35,
            reverb_mix: 0.0,
            reverb_decay_s: 1.8,
            reverb_damping: 0.5,
            exciter_amount: 0.0,
            exciter_hz: 3500.0,
            dyn_eq_range_db: 0.0,
            dyn_eq_hz: 150.0,
            dyn_eq_threshold_db: -30.0,
            dyn_eq_ratio: 3.0,
            true_peak_limiter: false,
        }
    }
}

pub(crate) fn bounded(value: f32, fallback: f32, min: f32, max: f32) -> f32 {
    if value.is_finite() {
        value.clamp(min, max)
    } else {
        fallback
    }
}

pub(crate) fn clean(value: f32) -> f32 {
    if value.is_finite() && value.abs() >= 1.0e-20 {
        value
    } else {
        0.0
    }
}
