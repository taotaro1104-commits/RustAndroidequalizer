#[derive(Clone, Copy)]
pub struct Dynamics {
    envelope: f32,
    limiter_gain: f32,
}

impl Default for Dynamics {
    fn default() -> Self {
        Self {
            envelope: 0.0,
            limiter_gain: 1.0,
        }
    }
}

impl Dynamics {
    pub fn compress(
        &mut self,
        frame: [f32; 2],
        attack: f32,
        release: f32,
        threshold_db: f32,
        ratio: f32,
    ) -> [f32; 2] {
        let peak = frame[0].abs().max(frame[1].abs());
        let rate = if peak > self.envelope {
            attack
        } else {
            release
        };
        self.envelope = peak + rate * (self.envelope - peak);
        let db = 20.0 * self.envelope.max(1.0e-12).log10();
        let reduction = (db - threshold_db).max(0.0) * (1.0 - 1.0 / ratio);
        let gain = 10.0_f32.powf(-reduction / 20.0);
        [frame[0] * gain, frame[1] * gain]
    }

    /// Stereo-linked sample-peak limiter, zero lookahead; not a true-peak limiter.
    pub fn limit(&mut self, frame: [f32; 2], release: f32) -> [f32; 2] {
        let peak = frame[0].abs().max(frame[1].abs());
        let wanted = if peak > 0.98 { 0.98 / peak } else { 1.0 };
        self.limiter_gain = wanted.min(1.0 - release * (1.0 - self.limiter_gain));
        [frame[0] * self.limiter_gain, frame[1] * self.limiter_gain]
    }
}
