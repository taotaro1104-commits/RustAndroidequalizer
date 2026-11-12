//! Dynamic low-band EQ: when the low band (below `hz`) gets louder than the threshold, only that
//! band is turned down (by at most `range_db`), so loud bass is tamed while quiet passages and the
//! mids/highs are untouched. The band is split with two cascaded one-pole low-passes (12 dB/oct) and
//! reduced by subtracting `(1 - g)` of it, which keeps the rest of the spectrum exactly as is.
use crate::params::{bounded, clean};

#[derive(Clone, Copy)]
pub struct PreparedDynEq {
    range_db: f32,
    alpha: f32,
    threshold_db: f32,
    ratio: f32,
    attack: f32,
    release: f32,
}

impl PreparedDynEq {
    /// `range_db == 0` disables the effect.
    pub fn new(range_db: f32, hz: f32, threshold_db: f32, ratio: f32, sample_rate: f32) -> Self {
        let f = bounded(hz, 150.0, 40.0, 600.0).min(sample_rate * 0.25);
        Self {
            range_db: bounded(range_db, 0.0, 0.0, 18.0),
            alpha: 1.0 - (-2.0 * std::f32::consts::PI * f / sample_rate).exp(),
            threshold_db: bounded(threshold_db, -30.0, -60.0, 0.0),
            ratio: bounded(ratio, 3.0, 1.0, 10.0),
            attack: (-1.0 / (sample_rate * 0.010)).exp(),
            release: (-1.0 / (sample_rate * 0.150)).exp(),
        }
    }
}

#[derive(Clone, Copy)]
pub struct DynEq {
    stage1: [f32; 2],
    stage2: [f32; 2],
    envelope: f32,
}

impl Default for DynEq {
    fn default() -> Self {
        Self { stage1: [0.0; 2], stage2: [0.0; 2], envelope: 0.0 }
    }
}

impl DynEq {
    pub fn process(&mut self, input: [f32; 2], c: PreparedDynEq) -> [f32; 2] {
        if c.range_db == 0.0 {
            *self = Self::default();
            return input;
        }
        let mut low = [0.0; 2];
        for ch in 0..2 {
            self.stage1[ch] = clean(self.stage1[ch] + c.alpha * (input[ch] - self.stage1[ch]));
            self.stage2[ch] = clean(self.stage2[ch] + c.alpha * (self.stage1[ch] - self.stage2[ch]));
            low[ch] = self.stage2[ch];
        }
        let level = low[0].abs().max(low[1].abs());
        let rate = if level > self.envelope { c.attack } else { c.release };
        self.envelope = level + rate * (self.envelope - level);
        let db = 20.0 * self.envelope.max(1.0e-9).log10();
        let reduction = ((db - c.threshold_db).max(0.0) * (1.0 - 1.0 / c.ratio)).min(c.range_db);
        let g = 10.0_f32.powf(-reduction / 20.0);
        [clean(input[0] - (1.0 - g) * low[0]), clean(input[1] - (1.0 - g) * low[1])]
    }
}
