use rustfft::{num_complex::Complex, FftPlanner};

/// Per tap: [L→L, R→L, L→R, R→R]. No channel folding in binaural rendering.
pub struct MatrixIr {
    pub taps: Vec<[f32; 4]>,
}

impl MatrixIr {
    pub fn identity() -> Self {
        Self {
            taps: vec![[1.0, 0.0, 0.0, 1.0]],
        }
    }

    pub fn stereo(frames: &[[f32; 2]], wet: f32) -> Result<Self, String> {
        if frames.is_empty() || !wet.is_finite() || !(0.0..=1.0).contains(&wet) {
            return Err("Invalid impulse response or wet mix".into());
        }
        let mut taps: Vec<_> = frames
            .iter()
            .map(|x| [x[0] * wet, 0.0, 0.0, x[1] * wet])
            .collect();
        taps[0][0] += 1.0 - wet;
        taps[0][3] += 1.0 - wet;
        Self::checked(taps)
    }

    pub fn checked(taps: Vec<[f32; 4]>) -> Result<Self, String> {
        if taps.is_empty()
            || taps.len() > 768000
            || taps
                .iter()
                .flatten()
                .any(|x| !x.is_finite() || x.abs() > 32.0)
        {
            return Err("IR must contain finite taps and fit the supported length".into());
        }
        Ok(Self { taps })
    }

    /// Compose a stereo-diagonal reverb after a 2×2 HRTF, on the worker only.
    pub fn then_stereo(self, reverb: &Self) -> Result<Self, String> {
        let length = self
            .taps
            .len()
            .checked_add(reverb.taps.len())
            .ok_or("IR too long")?
            - 1;
        if length > 768000 {
            return Err("Combined IR too long".into());
        }
        let n = length.next_power_of_two();
        let mut planner = FftPlanner::<f32>::new();
        let fft = planner.plan_fft_forward(n);
        let inverse = planner.plan_fft_inverse(n);
        let mut result = vec![[0.0; 4]; length];
        for channel in 0..4 {
            let mut a = vec![Complex::default(); n];
            let mut b = vec![Complex::default(); n];
            for (v, tap) in a.iter_mut().zip(&self.taps) {
                v.re = tap[channel];
            }
            for (v, tap) in b.iter_mut().zip(&reverb.taps) {
                v.re = tap[if channel < 2 { 0 } else { 3 }];
            }
            fft.process(&mut a);
            fft.process(&mut b);
            for (x, y) in a.iter_mut().zip(&b) {
                *x *= *y;
            }
            inverse.process(&mut a);
            for (tap, v) in result.iter_mut().zip(&a) {
                tap[channel] = v.re / n as f32;
            }
        }
        Self::checked(result)
    }
}

/// Own synthetic room response: deterministic, no third-party measurement data.
pub fn small_room(sample_rate: u32) -> Vec<[f32; 2]> {
    let mut frames = vec![[0.0; 2]; (sample_rate as f32 * 0.32) as usize + 1];
    frames[0] = [0.7; 2];
    for (i, ms) in [17.0, 29.0, 43.0, 61.0, 89.0, 127.0, 179.0, 251.0]
        .iter()
        .enumerate()
    {
        let at = (sample_rate as f32 * ms / 1000.0) as usize;
        let gain = 0.20 * 0.65_f32.powi(i as i32);
        frames[at][0] = gain;
        let right = (at + sample_rate as usize / 1000).min(frames.len() - 1);
        frames[right][1] = gain * if i % 2 == 0 { 1.0 } else { -1.0 };
    }
    frames
}
