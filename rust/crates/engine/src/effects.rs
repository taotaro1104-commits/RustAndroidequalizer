use crate::decoder::AudioFile;
use dsp_resample::StereoResampler;
use dsp_spatial::{matrix::small_room, Convolver, MatrixIr};
use rtrb::{Consumer, Producer, RingBuffer};
use std::path::Path;

pub struct PreparedSpatial {
    pub sample_rate: u32,
    convolver: Option<Convolver>,
}

impl PreparedSpatial {
    pub fn identity(sample_rate: u32) -> Self {
        Self {
            sample_rate,
            convolver: None,
        }
    }
    pub fn from_matrix(sample_rate: u32, ir: &MatrixIr, partition: usize) -> Result<Self, String> {
        Ok(Self {
            sample_rate,
            convolver: Some(Convolver::new(ir, partition)?),
        })
    }
    pub fn latency_frames(&self) -> usize {
        self.convolver.as_ref().map_or(0, Convolver::latency_frames)
    }
    pub fn tail_frames(&self) -> usize {
        self.convolver.as_ref().map_or(0, Convolver::tail_frames)
    }
    fn process(&mut self, input: [f32; 2]) -> [f32; 2] {
        self.convolver
            .as_mut()
            .map_or(input, |c| c.process_frame(input))
    }
}

/// Empty IR path selects the synthetic room; None disables reverb.
/// File decode, SOFA parsing and all planning run on the caller's worker thread.
pub fn prepare(
    sample_rate: u32,
    ir: Option<&Path>,
    sofa: Option<&Path>,
    azimuth: f32,
    wet: f32,
    quality: u32,
) -> Result<Box<PreparedSpatial>, String> {
    if !(8000..=384000).contains(&sample_rate) {
        return Err("Invalid sample rate".into());
    }
    if ir.is_none() && sofa.is_none() {
        return Ok(Box::new(PreparedSpatial::identity(sample_rate)));
    }
    if sample_rate > 192000 {
        return Err("Spatial modes support sample rates up to 192 kHz".into());
    }
    let partition = match quality {
        0 => 512,
        1 => 256,
        2 => 128,
        _ => return Err("Unknown spatial quality".into()),
    };
    let mut matrix = match sofa {
        Some(path) => dsp_spatial::hrtf::from_sofa(path, sample_rate, azimuth)?,
        None => MatrixIr::identity(),
    };
    if let Some(path) = ir {
        let mut frames = if path.as_os_str().is_empty() {
            small_room(sample_rate)
        } else {
            load_ir(path, sample_rate)?
        };
        // Lower tiers reduce both average cost and the worst convolution burst.
        // HRTF taps are preserved; only the reverb tail is shortened.
        let limit = match quality {
            0 => sample_rate as usize / 4,
            1 => sample_rate as usize * 2,
            _ => sample_rate as usize * 8,
        }
        .min(768001 - matrix.taps.len());
        if frames.len() > limit {
            frames.truncate(limit);
            let fade = (sample_rate as usize / 100).min(limit);
            for (i, frame) in frames[limit - fade..].iter_mut().enumerate() {
                let gain = (fade - 1 - i) as f32 / fade.max(1) as f32;
                frame[0] *= gain;
                frame[1] *= gain;
            }
        }
        let reverb = MatrixIr::stereo(&frames, wet)?;
        matrix = if sofa.is_some() {
            matrix.then_stereo(&reverb)?
        } else {
            reverb
        };
    }
    Ok(Box::new(PreparedSpatial::from_matrix(
        sample_rate,
        &matrix,
        partition,
    )?))
}

fn load_ir(path: &Path, sample_rate: u32) -> Result<Vec<[f32; 2]>, String> {
    let mut file = AudioFile::open(path)?;
    let mut converter = StereoResampler::new(file.sample_rate, sample_rate)?;
    let mut result = Vec::new();
    let mut input_frames = 0;
    while let Some(frames) = file.next_frames()? {
        input_frames += frames.len();
        if input_frames > file.sample_rate as usize * 8 {
            return Err("IR duration must be at most 8 seconds".into());
        }
        if frames.iter().flatten().any(|v| v.abs() > 8.0) {
            return Err("IR amplitude is out of range".into());
        }
        result.extend(converter.push(&frames)?);
    }
    result.extend(converter.finish()?);
    if result.is_empty() || result.iter().flatten().all(|v| v.abs() < 1.0e-12) {
        return Err("IR is empty or silent".into());
    }
    Ok(result)
}

pub struct EffectControl {
    incoming: Producer<Box<PreparedSpatial>>,
    retired: Consumer<Box<PreparedSpatial>>,
}
pub struct EffectSlot {
    incoming: Consumer<Box<PreparedSpatial>>,
    retired: Producer<Box<PreparedSpatial>>,
    active: Option<Box<PreparedSpatial>>,
    old: Option<Box<PreparedSpatial>>,
    warmup: usize,
    fade: usize,
    fade_length: usize,
}

pub fn channel(sample_rate: u32) -> (EffectControl, EffectSlot) {
    let (tx, rx) = RingBuffer::new(1);
    let (retired_tx, retired_rx) = RingBuffer::new(2);
    (
        EffectControl {
            incoming: tx,
            retired: retired_rx,
        },
        EffectSlot {
            incoming: rx,
            retired: retired_tx,
            active: None,
            old: None,
            warmup: 0,
            fade: 0,
            fade_length: (sample_rate as usize / 20).max(1),
        },
    )
}

impl EffectControl {
    pub fn collect(&mut self) {
        while let Ok(resource) = self.retired.pop() {
            drop(resource);
        }
    }
    /// On backpressure ownership returns to the control worker, never the callback.
    pub fn install(&mut self, effect: Box<PreparedSpatial>) -> Result<(), Box<PreparedSpatial>> {
        self.collect();
        self.incoming
            .push(effect)
            .map_err(|rtrb::PushError::Full(effect)| effect)
    }
}

impl EffectSlot {
    pub fn advance_silently(&mut self, frames: usize) {
        if self.fade != 0 {
            for _ in 0..frames {
                self.process([0.0; 2]);
            }
        }
    }
    pub fn adopt(&mut self) {
        if self.fade != 0 || self.warmup != 0 || self.old.is_some() || self.retired.slots() == 0 {
            return;
        }
        if let Ok(effect) = self.incoming.pop() {
            self.warmup = effect.latency_frames();
            self.old = self.active.take();
            self.active = Some(effect);
            self.fade = 1;
        }
    }
    pub fn tail_frames(&self) -> usize {
        self.active
            .as_ref()
            .map_or(0, |e| e.tail_frames())
            .max(self.old.as_ref().map_or(0, |e| e.tail_frames()))
    }
    pub fn process(&mut self, input: [f32; 2]) -> [f32; 2] {
        let new = self.active.as_mut().map_or(input, |p| p.process(input));
        if self.fade == 0 {
            return new;
        }
        let old = self.old.as_mut().map_or(input, |p| p.process(input));
        if self.warmup > 0 {
            self.warmup -= 1;
            return old;
        }
        let wet = (self.fade as f32 / self.fade_length as f32).min(1.0);
        let out = [
            old[0] + (new[0] - old[0]) * wet,
            old[1] + (new[1] - old[1]) * wet,
        ];
        if self.fade < self.fade_length {
            self.fade += 1;
        } else if let Some(resource) = self.old.take() {
            match self.retired.push(resource) {
                Ok(()) => self.fade = 0,
                Err(rtrb::PushError::Full(resource)) => self.old = Some(resource),
            }
        } else {
            self.fade = 0;
        }
        out
    }
}
