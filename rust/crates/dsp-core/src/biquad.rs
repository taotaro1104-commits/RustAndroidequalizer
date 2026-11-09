use crate::params::{bounded, clean, Band};

/// RBJ peaking EQ, normalized a0 = 1; coefficients are prepared off the audio thread.
#[derive(Clone, Copy, Debug)]
pub struct Coefficients {
    pub b: [f32; 3],
    pub a: [f32; 2],
}

impl Coefficients {
    pub const IDENTITY: Self = Self {
        b: [1.0, 0.0, 0.0],
        a: [0.0, 0.0],
    };

    pub fn peaking(band: Band, sample_rate: f32) -> Self {
        let sr = bounded(sample_rate, 48000.0, 8000.0, 384000.0) as f64;
        let frequency = bounded(band.frequency, 1000.0, 10.0, sr as f32 * 0.45) as f64;
        let gain = bounded(band.gain_db, 0.0, -24.0, 24.0) as f64;
        if gain.abs() < 0.00001 {
            return Self::IDENTITY;
        }
        let q = bounded(band.q, 1.0, 0.1, 18.0) as f64;
        let a = 10.0_f64.powf(gain / 40.0);
        let w = 2.0 * std::f64::consts::PI * frequency / sr;
        let alpha = w.sin() / (2.0 * q);
        let a0 = 1.0 + alpha / a;
        Self {
            b: [
                ((1.0 + alpha * a) / a0) as f32,
                (-2.0 * w.cos() / a0) as f32,
                ((1.0 - alpha * a) / a0) as f32,
            ],
            a: [
                (-2.0 * w.cos() / a0) as f32,
                ((1.0 - alpha / a) / a0) as f32,
            ],
        }
    }

    pub fn magnitude(&self, frequency: f32, sample_rate: f32) -> f32 {
        let w = 2.0 * std::f64::consts::PI * frequency as f64 / sample_rate as f64;
        let (c1, s1) = (w.cos(), -w.sin());
        let (c2, s2) = ((2.0 * w).cos(), -(2.0 * w).sin());
        let br = self.b[0] as f64 + self.b[1] as f64 * c1 + self.b[2] as f64 * c2;
        let bi = self.b[1] as f64 * s1 + self.b[2] as f64 * s2;
        let ar = 1.0 + self.a[0] as f64 * c1 + self.a[1] as f64 * c2;
        let ai = self.a[0] as f64 * s1 + self.a[1] as f64 * s2;
        ((br * br + bi * bi) / (ar * ar + ai * ai)).sqrt() as f32
    }
}

/// DF1 retains input/output history when coefficients change.
#[derive(Clone, Copy, Default)]
pub struct Biquad {
    x: [[f32; 2]; 2],
    y: [[f32; 2]; 2],
}

impl Biquad {
    #[inline]
    pub fn process(&mut self, frame: [f32; 2], c: Coefficients) -> [f32; 2] {
        std::array::from_fn(|ch| {
            let x = clean(frame[ch]);
            let y = clean(
                c.b[0] * x + c.b[1] * self.x[ch][0] + c.b[2] * self.x[ch][1]
                    - c.a[0] * self.y[ch][0]
                    - c.a[1] * self.y[ch][1],
            );
            self.x[ch] = [x, self.x[ch][0]];
            self.y[ch] = [y, self.y[ch][0]];
            y
        })
    }
}
