//! Harmonic exciter: isolates the highs, passes them through an asymmetric soft saturator and adds
//! only the generated harmonics (the saturator's residual) back to the dry signal. Even-order terms
//! come from the asymmetry; a DC blocker removes the offset they create. State is two scalars per channel.
use crate::params::{bounded, clean};

#[derive(Clone, Copy)]
pub struct PreparedExciter {
    amount: f32,
    alpha: f32,
}

impl PreparedExciter {
    /// `amount == 0` disables the effect. `hz` is the frequency above which harmonics are generated.
    pub fn new(amount: f32, hz: f32, sample_rate: f32) -> Self {
        let f = bounded(hz, 3500.0, 1500.0, 9000.0).min(sample_rate * 0.4);
        Self {
            amount: bounded(amount, 0.0, 0.0, 1.0),
            alpha: 1.0 - (-2.0 * std::f32::consts::PI * f / sample_rate).exp(),
        }
    }
}

#[derive(Clone, Copy, Default)]
pub struct Exciter {
    lowpass: [f32; 2],
    dc: [f32; 2],
    dc_in: [f32; 2],
}

impl Exciter {
    pub fn process(&mut self, input: [f32; 2], c: PreparedExciter) -> [f32; 2] {
        if c.amount == 0.0 {
            *self = Self::default();
            return input;
        }
        let mut out = input;
        for ch in 0..2 {
            self.lowpass[ch] = clean(self.lowpass[ch] + c.alpha * (input[ch] - self.lowpass[ch]));
            let high = input[ch] - self.lowpass[ch];
            let driven = high + 0.3 * high * high; // the even-order term creates 2nd harmonics (and DC, blocked below)
            let harmonics = (3.0 * driven).tanh() / 3.0 - high;
            // One-pole DC blocker on the generated part.
            let blocked = harmonics - self.dc_in[ch] + 0.995 * self.dc[ch];
            self.dc_in[ch] = harmonics;
            self.dc[ch] = clean(blocked);
            out[ch] = clean(input[ch] + c.amount * 1.5 * blocked);
        }
        out
    }
}
