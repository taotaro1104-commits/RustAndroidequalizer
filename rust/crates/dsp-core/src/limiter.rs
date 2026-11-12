//! Look-ahead true-peak limiter (stereo-linked). Inter-sample peaks are estimated with 4-point
//! Catmull-Rom interpolation at 1/4, 1/2 and 3/4 between samples (about 4x oversampling). The gain
//! is the box average of a sliding minimum, which guarantees the gain never exceeds the gain
//! required by any sample in its look-ahead window; an exponential release then recovers slowly so
//! low-frequency material is not modulated. All buffers are allocated in `new` (control side).
use crate::params::{bounded, clean};

/// Ceiling of about -0.5 dBTP.
pub const CEILING: f32 = 0.944;
/// Look-ahead in milliseconds; the output is delayed by `lookahead - 1` frames.
pub const LOOKAHEAD_MS: f32 = 1.0;

pub struct TruePeakLimiter {
    frames: Vec<[f32; 2]>,
    required: Vec<f32>,
    minima: Vec<f32>,
    sum: f32,
    pos: usize,
    history: [[f32; 2]; 3],
    gain: f32,
}

impl TruePeakLimiter {
    pub fn new(sample_rate: f32) -> Self {
        let sr = bounded(sample_rate, 48000.0, 8000.0, 384000.0);
        let n = ((sr * LOOKAHEAD_MS * 0.001) as usize).clamp(8, 512);
        Self {
            frames: vec![[0.0; 2]; n],
            required: vec![1.0; n],
            minima: vec![1.0; n],
            sum: n as f32,
            pos: 0,
            history: [[0.0; 2]; 3],
            gain: 1.0,
        }
    }

    /// Delay added to the signal, in frames.
    pub fn latency(&self) -> usize {
        self.frames.len() - 1
    }

    fn catmull(p0: f32, p1: f32, p2: f32, p3: f32, t: f32) -> f32 {
        0.5 * ((2.0 * p1)
            + (-p0 + p2) * t
            + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t * t
            + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t * t * t)
    }

    pub fn process(&mut self, input: [f32; 2], release: f32) -> [f32; 2] {
        let n = self.frames.len();
        let x = input.map(clean);
        let mut peak = x[0].abs().max(x[1].abs());
        // Peaks between the previous two frames, found with one frame of extra context.
        for ch in 0..2 {
            let (p0, p1, p2) = (self.history[2][ch], self.history[1][ch], self.history[0][ch]);
            for t in [0.25_f32, 0.5, 0.75] {
                peak = peak.max(Self::catmull(p0, p1, p2, x[ch], t).abs());
            }
        }
        self.history = [x, self.history[0], self.history[1]];

        self.required[self.pos] = if peak > CEILING { CEILING / peak } else { 1.0 };
        let window_min = self.required.iter().fold(1.0_f32, |m, v| m.min(*v));
        self.sum += window_min - self.minima[self.pos];
        self.minima[self.pos] = window_min;
        let smoothed = (self.sum / n as f32).clamp(0.0, 1.0);
        // Instant when more reduction is needed (already smoothed by the box), slow recovery otherwise.
        self.gain = smoothed.min(self.gain + (1.0 - self.gain) * (1.0 - release));

        self.frames[self.pos] = x;
        let out = self.frames[(self.pos + 1) % n];
        self.pos = (self.pos + 1) % n;
        [out[0] * self.gain, out[1] * self.gain]
    }
}
