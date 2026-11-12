//! Stereo feedback echo. The delay line is allocated once in `Echo::new`
//! (control side); processing never allocates or locks. Time and mix are slewed
//! so changing them while playing does not click.
use crate::params::{bounded, clean};

pub const MIN_ECHO_MS: f32 = 20.0;
pub const MAX_ECHO_MS: f32 = 1000.0;
pub const MAX_ECHO_FEEDBACK: f32 = 0.85;

#[derive(Clone, Copy)]
pub struct PreparedEcho {
    mix: f32,
    delay_samples: f32,
    feedback: f32,
    damping: f32,
}
impl PreparedEcho {
    /// `mix == 0` disables the effect.
    pub fn new(mix: f32, time_ms: f32, feedback: f32, sample_rate: f32) -> Self {
        let ms = bounded(time_ms, 300.0, MIN_ECHO_MS, MAX_ECHO_MS);
        Self {
            mix: bounded(mix, 0.0, 0.0, 1.0),
            delay_samples: ms * 0.001 * sample_rate,
            feedback: bounded(feedback, 0.35, 0.0, MAX_ECHO_FEEDBACK),
            // Repeats get gradually darker (one-pole low-pass at ~4 kHz).
            damping: 1.0 - (-2.0 * std::f32::consts::PI * 4000.0 / sample_rate).exp(),
        }
    }
}

pub struct Echo {
    buffer: Vec<[f32; 2]>,
    write: usize,
    delay: f32,
    wet: f32,
    lowpass: [f32; 2],
    slew: f32,
}
impl Echo {
    pub fn new(sample_rate: f32) -> Self {
        let sr = bounded(sample_rate, 48000.0, 8000.0, 384000.0);
        Self {
            buffer: vec![[0.0; 2]; (sr * MAX_ECHO_MS * 0.001) as usize + 4],
            write: 0,
            delay: 0.3 * sr,
            wet: 0.0,
            lowpass: [0.0; 2],
            slew: 1.0 - (-1.0 / (sr * 0.020)).exp(),
        }
    }

    pub fn process(&mut self, input: [f32; 2], c: PreparedEcho) -> [f32; 2] {
        // Fully faded out: clear state so old repeats never replay on re-enable.
        if c.mix == 0.0 && self.wet < 1.0e-5 {
            if self.wet != 0.0 || self.write != 0 {
                self.buffer.iter_mut().for_each(|f| *f = [0.0; 2]);
                self.lowpass = [0.0; 2];
                self.wet = 0.0;
                self.write = 0;
            }
            return input;
        }
        self.wet += (c.mix - self.wet) * self.slew;
        self.delay += (c.delay_samples - self.delay) * self.slew;
        let len = self.buffer.len();
        let delay = self.delay.clamp(1.0, (len - 3) as f32);
        let whole = delay as usize;
        let frac = delay - whole as f32;
        let at = |back: usize| self.buffer[(self.write + len - back) % len];
        let (a, b) = (at(whole), at(whole + 1));
        let mut out = input;
        let mut feed = [0.0; 2];
        for ch in 0..2 {
            let tap = a[ch] + (b[ch] - a[ch]) * frac;
            self.lowpass[ch] = clean(self.lowpass[ch] + c.damping * (tap - self.lowpass[ch]));
            out[ch] = clean(input[ch] + self.wet * tap);
            feed[ch] = clean(input[ch] + c.feedback * self.lowpass[ch]).clamp(-8.0, 8.0);
        }
        self.buffer[self.write] = feed;
        self.write = (self.write + 1) % len;
        out
    }
}
