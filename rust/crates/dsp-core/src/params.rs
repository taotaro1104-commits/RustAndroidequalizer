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
