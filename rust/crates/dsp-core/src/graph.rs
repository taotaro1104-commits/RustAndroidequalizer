use crate::biquad::{Biquad, Coefficients};
use crate::dyneq::{DynEq, PreparedDynEq};
use crate::dynamics::Dynamics;
use crate::echo::{Echo, PreparedEcho};
use crate::exciter::{Exciter, PreparedExciter};
use crate::limiter::TruePeakLimiter;
use crate::reverb::{PreparedReverb, Reverb};
use crate::params::{bounded, clean, Config, SpatialMode, MAX_BANDS};
use crate::virtual_bass::{PreparedBass, VirtualBass};

#[derive(Clone, Copy)]
pub struct PreparedConfig {
    pub coefficients: [Coefficients; MAX_BANDS],
    pub band_count: usize,
    gain: f32,
    pub bypass: bool,
    compressor: bool,
    threshold_db: f32,
    ratio: f32,
    width: f32,
    crossfeed: f32,
    crossfeed_alpha: f32,
    attack: f32,
    release: f32,
    limiter_release: f32,
    bass: PreparedBass,
    echo: PreparedEcho,
    reverb: PreparedReverb,
    exciter: PreparedExciter,
    dyneq: PreparedDynEq,
    true_peak: bool,
}

impl PreparedConfig {
    pub fn new(config: &Config, sample_rate: f32) -> Self {
        let sr = bounded(sample_rate, 48000.0, 8000.0, 384000.0);
        Self {
            coefficients: config.bands.map(|b| Coefficients::peaking(b, sr)),
            band_count: config.band_count.min(MAX_BANDS),
            gain: 10.0_f32.powf(bounded(config.preamp_db, 0.0, -60.0, 12.0) / 20.0),
            bypass: config.bypass,
            compressor: config.compressor,
            threshold_db: bounded(config.threshold_db, -18.0, -60.0, 0.0),
            ratio: bounded(config.ratio, 3.0, 1.0, 20.0),
            width: if config.spatial == SpatialMode::Wide {
                bounded(config.width, 1.0, 0.0, 2.0)
            } else {
                1.0
            },
            crossfeed: if config.spatial == SpatialMode::Crossfeed {
                bounded(config.crossfeed, 0.25, 0.0, 0.5)
            } else {
                0.0
            },
            crossfeed_alpha: 1.0 - (-2.0 * std::f32::consts::PI * 700.0 / sr).exp(),
            attack: (-1.0 / (sr * 0.010)).exp(),
            release: (-1.0 / (sr * 0.100)).exp(),
            limiter_release: (-1.0 / (sr * 0.050)).exp(),
            bass: PreparedBass::new(config.virtual_bass_amount, config.virtual_bass_hz, sr),
            echo: PreparedEcho::new(
                config.echo_mix,
                config.echo_time_ms,
                config.echo_feedback,
                sr,
            ),
            reverb: PreparedReverb::new(
                config.reverb_mix,
                config.reverb_decay_s,
                config.reverb_damping,
                sr,
            ),
            exciter: PreparedExciter::new(config.exciter_amount, config.exciter_hz, sr),
            dyneq: PreparedDynEq::new(
                config.dyn_eq_range_db,
                config.dyn_eq_hz,
                config.dyn_eq_threshold_db,
                config.dyn_eq_ratio,
                sr,
            ),
            true_peak: config.true_peak_limiter,
        }
    }
}

#[derive(Clone, Copy)]
struct Pipeline {
    config: PreparedConfig,
    filters: [Biquad; MAX_BANDS],
    dynamics: Dynamics,
    bass: VirtualBass,
    lowpass: [f32; 2],
}

impl Pipeline {
    fn process(&mut self, dry: [f32; 2]) -> [f32; 2] {
        let c = self.config;
        if c.bypass {
            return dry;
        }
        let mut x = [dry[0] * c.gain, dry[1] * c.gain];
        for i in 0..c.band_count {
            x = self.filters[i].process(x, c.coefficients[i]);
        }
        x = self.bass.process(x, c.bass);
        if c.compressor {
            x = self
                .dynamics
                .compress(x, c.attack, c.release, c.threshold_db, c.ratio);
        }
        let mid = 0.5 * (x[0] + x[1]);
        let side = 0.5 * (x[0] - x[1]) * c.width;
        x = [mid + side, mid - side];
        for (ch, sample) in x.iter().enumerate() {
            self.lowpass[ch] =
                clean(self.lowpass[ch] + c.crossfeed_alpha * (*sample - self.lowpass[ch]));
        }
        if c.crossfeed > 0.0 {
            x = [
                (x[0] + c.crossfeed * self.lowpass[1]) / (1.0 + c.crossfeed),
                (x[1] + c.crossfeed * self.lowpass[0]) / (1.0 + c.crossfeed),
            ];
        }
        x
    }
}

pub struct Dsp {
    active: Pipeline,
    next: Pipeline,
    pending: Option<PreparedConfig>,
    fade_position: usize,
    fade_length: usize,
    output_dynamics: Dynamics,
    echo: Echo,
    reverb: Reverb,
    exciter: Exciter,
    dyneq: DynEq,
    peak_limiter: TruePeakLimiter,
}

impl Dsp {
    pub fn new(sample_rate: f32, config: PreparedConfig) -> Self {
        let pipeline = Pipeline {
            config,
            filters: [Biquad::default(); MAX_BANDS],
            dynamics: Dynamics::default(),
            bass: VirtualBass::default(),
            lowpass: [0.0; 2],
        };
        Self {
            active: pipeline,
            next: pipeline,
            pending: None,
            fade_position: 0,
            fade_length: (bounded(sample_rate, 48000.0, 8000.0, 384000.0) * 0.020) as usize,
            output_dynamics: Dynamics::default(),
            echo: Echo::new(sample_rate),
            reverb: Reverb::new(sample_rate),
            exciter: Exciter::default(),
            dyneq: DynEq::default(),
            peak_limiter: TruePeakLimiter::new(sample_rate),
        }
    }

    /// Latest update wins; active transitions complete before starting another.
    pub fn update(&mut self, config: PreparedConfig) {
        self.pending = Some(config);
    }

    pub fn process_frame(&mut self, input: [f32; 2]) -> [f32; 2] {
        let output = self.process_unlimited(input);
        self.limit_output(output)
    }

    /// Engine inserts prepared convolution before the final output limiter.
    pub fn process_unlimited(&mut self, input: [f32; 2]) -> [f32; 2] {
        if self.fade_position == 0 {
            if let Some(config) = self.pending.take() {
                self.next = self.active;
                self.next.config = config;
                self.fade_position = 1;
            }
        }
        let input = input.map(|v| clean(v).clamp(-32.0, 32.0));
        let a = self.active.process(input);
        let output = if self.fade_position > 0 {
            let b = self.next.process(input);
            let wet = self.fade_position as f32 / self.fade_length as f32;
            let mixed = [a[0] + wet * (b[0] - a[0]), a[1] + wet * (b[1] - a[1])];
            self.fade_position += 1;
            if self.fade_position > self.fade_length {
                self.active = self.next;
                self.fade_position = 0;
            }
            mixed
        } else {
            a
        };
        let output = if self.active.config.bypass {
            output
        } else {
            let c = self.active.config;
            let shaped = self.dyneq.process(output, c.dyneq);
            let excited = self.exciter.process(shaped, c.exciter);
            let echoed = self.echo.process(excited, c.echo);
            self.reverb.process(echoed, c.reverb)
        };
        output.map(clean)
    }

    pub fn limit_output(&mut self, input: [f32; 2]) -> [f32; 2] {
        let input = input.map(clean);
        let c = self.active.config;
        let out = if c.bypass {
            input
        } else if c.true_peak {
            self.peak_limiter.process(input, c.limiter_release)
        } else {
            self.output_dynamics.limit(input, c.limiter_release)
        };
        out.map(|x| clean(x).clamp(-0.999, 0.999))
    }

    pub fn process_interleaved(&mut self, samples: &mut [f32]) {
        for frame in samples.as_chunks_mut::<2>().0 {
            let out = self.process_frame([frame[0], frame[1]]);
            frame.copy_from_slice(&out);
        }
    }
}
