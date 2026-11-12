//! Lightweight room reverb: a 4-line feedback delay network (FDN) with an orthogonal Hadamard
//! feedback matrix, per-line decay gains set from the RT60 and a damping low-pass in each line.
//! Delay lines are allocated once in `Reverb::new`; processing never allocates or locks.
use crate::params::{bounded, clean};

pub const MIN_DECAY_S: f32 = 0.2;
pub const MAX_DECAY_S: f32 = 6.0;
/// Delay lengths in samples at 48 kHz (mutually non-harmonic, 30-43 ms).
const LENGTHS_48K: [f32; 4] = [1433.0, 1601.0, 1867.0, 2053.0];

#[derive(Clone, Copy)]
pub struct PreparedReverb {
    mix: f32,
    gains: [f32; 4],
    damping: f32,
    lengths: [usize; 4],
}

impl PreparedReverb {
    /// `mix == 0` disables the effect. `decay_s` is the RT60; `damping` 0 (bright) .. 1 (dark).
    pub fn new(mix: f32, decay_s: f32, damping: f32, sample_rate: f32) -> Self {
        let scale = sample_rate / 48000.0;
        let lengths = LENGTHS_48K.map(|l| ((l * scale) as usize).max(8));
        let rt60 = bounded(decay_s, 1.8, MIN_DECAY_S, MAX_DECAY_S);
        // -60 dB after rt60 seconds: g = 10^(-3 * delay / (rt60 * sr)).
        let gains = lengths.map(|d| 10.0_f32.powf(-3.0 * d as f32 / (rt60 * sample_rate)));
        let d = bounded(damping, 0.5, 0.0, 1.0);
        let cutoff = 1500.0 + (1.0 - d) * 12500.0;
        Self {
            mix: bounded(mix, 0.0, 0.0, 1.0),
            gains,
            damping: 1.0 - (-2.0 * std::f32::consts::PI * cutoff / sample_rate).exp(),
            lengths,
        }
    }
}

pub struct Reverb {
    lines: [Vec<f32>; 4],
    write: [usize; 4],
    lowpass: [f32; 4],
    wet: f32,
    slew: f32,
}

impl Reverb {
    pub fn new(sample_rate: f32) -> Self {
        let sr = bounded(sample_rate, 48000.0, 8000.0, 384000.0);
        let scale = sr / 48000.0;
        Self {
            lines: LENGTHS_48K.map(|l| vec![0.0; (l * scale) as usize + 8]),
            write: [0; 4],
            lowpass: [0.0; 4],
            wet: 0.0,
            slew: 1.0 - (-1.0 / (sr * 0.020)).exp(),
        }
    }

    fn clear(&mut self) {
        for line in self.lines.iter_mut() {
            line.iter_mut().for_each(|v| *v = 0.0);
        }
        self.write = [0; 4];
        self.lowpass = [0.0; 4];
        self.wet = 0.0;
    }

    pub fn process(&mut self, input: [f32; 2], c: PreparedReverb) -> [f32; 2] {
        if c.mix == 0.0 && self.wet < 1.0e-5 {
            if self.wet != 0.0 || self.write != [0; 4] {
                self.clear();
            }
            return input;
        }
        self.wet += (c.mix - self.wet) * self.slew;
        // Read the oldest sample of every line (a delay of exactly its length).
        let mut taps = [0.0_f32; 4];
        for i in 0..4 {
            let len = self.lines[i].len();
            let d = c.lengths[i].min(len - 1);
            taps[i] = self.lines[i][(self.write[i] + len - d) % len];
        }
        // Hadamard feedback (orthogonal, scaled by 1/2).
        let h = [
            0.5 * (taps[0] + taps[1] + taps[2] + taps[3]),
            0.5 * (taps[0] - taps[1] + taps[2] - taps[3]),
            0.5 * (taps[0] + taps[1] - taps[2] - taps[3]),
            0.5 * (taps[0] - taps[1] - taps[2] + taps[3]),
        ];
        let mono = 0.5 * (input[0] + input[1]);
        for i in 0..4 {
            let fed = clean(h[i] * c.gains[i]);
            self.lowpass[i] = clean(self.lowpass[i] + c.damping * (fed - self.lowpass[i]));
            let len = self.lines[i].len();
            self.lines[i][self.write[i]] = clean(mono * 0.35 + self.lowpass[i]).clamp(-8.0, 8.0);
            self.write[i] = (self.write[i] + 1) % len;
        }
        let left = 0.5 * (taps[0] + taps[2]);
        let right = 0.5 * (taps[1] + taps[3]);
        [clean(input[0] + self.wet * left), clean(input[1] + self.wet * right)]
    }
}
