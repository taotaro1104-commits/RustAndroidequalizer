//! Missing-fundamental bass enhancement: low-band extraction, soft full-wave
//! shaping and band-limited harmonics. No lookahead, allocation or locks.
use crate::biquad::Coefficients;
use crate::params::{bounded, clean};

/// Upper bound of the bass strength. 1.0 is the original maximum; values up
/// to 2.0 add proportionally stronger harmonics (the final limiter still applies).
pub const MAX_BASS_AMOUNT: f32 = 2.0;

#[derive(Clone, Copy)]
pub struct PreparedBass {
    pub amount: f32,
    low: Coefficients,
    high: Coefficients,
    harmonics: Coefficients,
}
impl PreparedBass {
    pub fn new(amount: f32, frequency: f32, sample_rate: f32) -> Self {
        let sr = bounded(sample_rate, 48000.0, 8000.0, 384000.0);
        let hz = bounded(frequency, 110.0, 60.0, 180.0);
        Self {
            amount: bounded(amount, 0.0, 0.0, MAX_BASS_AMOUNT),
            low: pass(hz, sr, false),
            high: pass(hz * 0.8, sr, true),
            harmonics: pass(hz * 4.0, sr, false),
        }
    }
}
fn pass(hz: f32, sr: f32, high: bool) -> Coefficients {
    let w = 2.0 * std::f64::consts::PI * hz as f64 / sr as f64;
    let alpha = w.sin() / std::f64::consts::SQRT_2;
    let a0 = 1.0 + alpha;
    let b0 = if high {
        (1.0 + w.cos()) * 0.5
    } else {
        (1.0 - w.cos()) * 0.5
    };
    let b1 = if high { -2.0 * b0 } else { 2.0 * b0 };
    Coefficients {
        b: [(b0 / a0) as f32, (b1 / a0) as f32, (b0 / a0) as f32],
        a: [(-2.0 * w.cos() / a0) as f32, ((1.0 - alpha) / a0) as f32],
    }
}
#[derive(Clone, Copy, Default)]
struct MonoFilter {
    x: [f32; 2],
    y: [f32; 2],
}
impl MonoFilter {
    fn process(&mut self, input: f32, c: Coefficients) -> f32 {
        let y = clean(
            c.b[0] * input + c.b[1] * self.x[0] + c.b[2] * self.x[1]
                - c.a[0] * self.y[0]
                - c.a[1] * self.y[1],
        );
        self.x = [input, self.x[0]];
        self.y = [y, self.y[0]];
        y
    }
}
#[derive(Clone, Copy, Default)]
pub struct VirtualBass {
    low: MonoFilter,
    high: MonoFilter,
    harmonics: MonoFilter,
}
impl VirtualBass {
    pub fn process(&mut self, input: [f32; 2], c: PreparedBass) -> [f32; 2] {
        if c.amount == 0.0 {
            // Do not replay a frozen filter tail when the effect is enabled again.
            *self = Self::default();
            return input;
        }
        let low = self.low.process(clean(0.5 * (input[0] + input[1])), c.low);
        // Soft limiting bounds the generated signal even with excessive input.
        // Rectification adds even harmonics; the HP removes its DC component.
        let shaped = (low * 4.0).tanh().abs() * 0.25;
        let ac = self.high.process(shaped, c.high);
        let harmonic = self.harmonics.process(ac, c.harmonics) * c.amount * 1.8;
        [clean(input[0] + harmonic), clean(input[1] + harmonic)]
    }
}
