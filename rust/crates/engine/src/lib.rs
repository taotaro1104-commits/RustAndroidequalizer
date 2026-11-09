//! Control and render ownership are disjoint. Only Copy payloads cross the RT queues.
mod decoder;
pub mod effects;
use decoder::AudioFile;
use dsp_core::{Config, Dsp, PreparedConfig};
use dsp_resample::StereoResampler;
use rtrb::{Consumer, Producer, RingBuffer};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

#[derive(Default)]
pub struct Status {
    pub playing: AtomicBool,
    pub ready: AtomicBool,
    pub decoded_done: AtomicBool,
    pub finished: AtomicBool,
    pub cancelled: AtomicBool,
    pub rendered_frames: AtomicU64,
    pub underrun_callbacks: AtomicU64,
    pub peak_bits: AtomicU32,
    pub error: Mutex<Option<String>>,
}

pub struct Controller {
    sample_rate: u32,
    commands: Producer<PreparedConfig>,
    pcm: Option<Producer<[f32; 2]>>,
    worker: Option<JoinHandle<()>>,
    pub status: Arc<Status>,
    pub effects: effects::EffectControl,
    pub duration_seconds: f64,
    pub start_seconds: f64,
}

pub struct Renderer {
    dsp: Dsp,
    commands: Consumer<PreparedConfig>,
    pcm: Consumer<[f32; 2]>,
    status: Arc<Status>,
    effects: effects::EffectSlot,
    tail_remaining: Option<usize>,
    minimum_tail: usize,
    bypass_wet: f32,
    bypass_target: f32,
    bypass_step: f32,
}

pub fn create(sample_rate: u32) -> (Controller, Renderer) {
    let sample_rate = sample_rate.clamp(8000, 384000);
    let (command_tx, command_rx) = RingBuffer::new(8);
    let (pcm_tx, pcm_rx) = RingBuffer::new(sample_rate as usize / 2);
    let status = Arc::new(Status::default());
    let (effect_control, effect_slot) = effects::channel(sample_rate);
    let controller = Controller {
        sample_rate,
        commands: command_tx,
        pcm: Some(pcm_tx),
        worker: None,
        status: status.clone(),
        effects: effect_control,
        duration_seconds: 0.0,
        start_seconds: 0.0,
    };
    let renderer = Renderer {
        dsp: Dsp::new(
            sample_rate as f32,
            PreparedConfig::new(&Config::default(), sample_rate as f32),
        ),
        commands: command_rx,
        pcm: pcm_rx,
        status,
        effects: effect_slot,
        tail_remaining: None,
        minimum_tail: sample_rate as usize / 10,
        bypass_wet: 1.0,
        bypass_target: 1.0,
        bypass_step: 1.0 / (sample_rate as f32 * 0.020),
    };
    (controller, renderer)
}

impl Controller {
    /// false means queue full; the control owner must retry its latest config.
    pub fn configure(&mut self, config: &Config) -> bool {
        self.effects.collect();
        self.commands
            .push(PreparedConfig::new(config, self.sample_rate as f32))
            .is_ok()
    }

    pub fn load_file(&mut self, path: &Path) -> Result<(), String> {
        self.load_file_at(path, 0.0)
    }

    pub fn load_file_at(&mut self, path: &Path, seconds: f64) -> Result<(), String> {
        let mut file = AudioFile::open(path)?;
        file.seek(seconds)?;
        self.duration_seconds = file.duration_seconds;
        self.start_seconds = file.start_seconds;
        let resampler = StereoResampler::new(file.sample_rate, self.sample_rate)?;
        self.spawn(move |mut pcm, status| {
            let mut file = file;
            let mut resampler = resampler;
            loop {
                if status.cancelled.load(Ordering::Acquire) {
                    return Ok(());
                }
                match file.next_frames()? {
                    Some(input) => {
                        if !send_frames(&mut pcm, &status, &resampler.push(&input)?) {
                            return Ok(());
                        }
                    }
                    None => {
                        send_frames(&mut pcm, &status, &resampler.finish()?);
                        return Ok(());
                    }
                }
            }
        })
    }

    pub fn load_demo(&mut self) -> Result<(), String> {
        self.load_demo_at(0.0)
    }

    pub fn load_demo_at(&mut self, seconds: f64) -> Result<(), String> {
        if !seconds.is_finite() || seconds < 0.0 {
            return Err("Invalid demo position".into());
        }
        let sr = self.sample_rate;
        let start_frame = ((seconds.min(60.0 - 1.0 / sr as f64)) * sr as f64).floor() as usize;
        self.duration_seconds = 60.0;
        self.start_seconds = start_frame as f64 / sr as f64;
        self.spawn(move |mut pcm, status| {
            // Quiet 60-second stereo demo; useful without storage/media permissions.
            for start in (start_frame..sr as usize * 60).step_by(1024) {
                let frames: Vec<_> = (start..(start + 1024).min(sr as usize * 60))
                    .map(|i| {
                        let t = i as f32 / sr as f32;
                        let fade = (t / 0.05).min(1.0) * ((60.0 - t) / 0.05).min(1.0);
                        let l = 0.10 * (2.0 * std::f32::consts::PI * 220.0 * t).sin()
                            + 0.05 * (2.0 * std::f32::consts::PI * 880.0 * t).sin();
                        let r = 0.10 * (2.0 * std::f32::consts::PI * 330.0 * t).sin()
                            + 0.05 * (2.0 * std::f32::consts::PI * 1760.0 * t).sin();
                        [l * fade, r * fade]
                    })
                    .collect();
                if !send_frames(&mut pcm, &status, &frames) {
                    break;
                }
            }
            Ok(())
        })
    }

    fn spawn(
        &mut self,
        job: impl FnOnce(Producer<[f32; 2]>, Arc<Status>) -> Result<(), String> + Send + 'static,
    ) -> Result<(), String> {
        let pcm = self
            .pcm
            .take()
            .ok_or("Source already loaded; create a new engine to change source")?;
        let status = self.status.clone();
        self.worker = Some(
            thread::Builder::new()
                .name("eq-decode".into())
                .spawn(move || {
                    if let Err(e) = job(pcm, status.clone()) {
                        *status.error.lock().unwrap_or_else(|e| e.into_inner()) = Some(e);
                    }
                    status.decoded_done.store(true, Ordering::Release);
                    status.ready.store(true, Ordering::Release);
                })
                .map_err(|e| e.to_string())?,
        );
        Ok(())
    }

    pub fn wait_ready(&self, timeout: Duration) -> Result<(), String> {
        let start = Instant::now();
        while !self.status.ready.load(Ordering::Acquire) {
            if start.elapsed() > timeout {
                return Err("Decoder startup timed out".into());
            }
            thread::sleep(Duration::from_millis(2));
        }
        self.error().map_or(Ok(()), Err)
    }

    pub fn error(&self) -> Option<String> {
        self.status
            .error
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    pub fn set_playing(&self, playing: bool) {
        self.status.playing.store(playing, Ordering::Release);
    }
}

impl Drop for Controller {
    fn drop(&mut self) {
        self.status.cancelled.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

fn send_frames(pcm: &mut Producer<[f32; 2]>, status: &Status, frames: &[[f32; 2]]) -> bool {
    for frame in frames {
        loop {
            if status.cancelled.load(Ordering::Acquire) {
                return false;
            }
            if pcm.push(*frame).is_ok() {
                break;
            }
            status.ready.store(true, Ordering::Release);
            thread::sleep(Duration::from_millis(2));
        }
    }
    if pcm.slots() < 4096 {
        status.ready.store(true, Ordering::Release);
    }
    true
}

impl Renderer {
    pub fn render(&mut self, output: &mut [f32]) {
        // Fixed drain budget, even if the producer publishes concurrently.
        let mut latest = None;
        for _ in 0..8 {
            match self.commands.pop() {
                Ok(c) => latest = Some(c),
                Err(_) => break,
            }
        }
        if let Some(c) = latest {
            self.bypass_target = if c.bypass { 0.0 } else { 1.0 };
            self.dsp.update(c);
        }
        self.effects.adopt();
        if !self.status.playing.load(Ordering::Acquire)
            || self.status.finished.load(Ordering::Acquire)
        {
            self.effects.advance_silently(output.len() / 2);
            output.fill(0.0);
            self.status.peak_bits.store(0, Ordering::Relaxed);
            return;
        }
        let mut missing = false;
        let mut rendered = 0;
        let mut peak = 0.0_f32;
        for frame in output.as_chunks_mut::<2>().0 {
            let input = match self.pcm.pop() {
                Ok(x) => {
                    self.tail_remaining = None;
                    rendered += 1;
                    x
                }
                Err(_) => {
                    missing = true;
                    if self.status.decoded_done.load(Ordering::Acquire) && self.pcm.is_empty() {
                        let remaining = self
                            .tail_remaining
                            .get_or_insert(self.effects.tail_frames().max(self.minimum_tail));
                        *remaining = remaining.saturating_sub(1);
                    }
                    [0.0; 2]
                }
            };
            let base = self.dsp.process_unlimited(input);
            let effected = self.effects.process(base);
            self.bypass_wet +=
                (self.bypass_target - self.bypass_wet).clamp(-self.bypass_step, self.bypass_step);
            let mixed = [
                base[0] + (effected[0] - base[0]) * self.bypass_wet,
                base[1] + (effected[1] - base[1]) * self.bypass_wet,
            ];
            let out = self.dsp.limit_output(mixed);
            peak = peak.max(out[0].abs()).max(out[1].abs());
            frame.copy_from_slice(&out);
        }
        if !output.len().is_multiple_of(2) {
            if let Some(last) = output.last_mut() {
                *last = 0.0;
            }
        }
        self.status
            .peak_bits
            .store(peak.to_bits(), Ordering::Relaxed);
        self.status
            .rendered_frames
            .fetch_add(rendered, Ordering::Relaxed);
        if missing {
            if self.tail_remaining == Some(0) {
                self.status.finished.store(true, Ordering::Release);
                self.status.playing.store(false, Ordering::Release);
            } else if !self.status.decoded_done.load(Ordering::Acquire) {
                self.status
                    .underrun_callbacks
                    .fetch_add(1, Ordering::Relaxed);
            }
        }
    }
}

#[cfg(test)]
mod graph_tests {
    use super::*;
    #[test]
    fn prepared_effect_reaches_output_and_master_bypass_removes_it() {
        let (mut control, mut renderer) = create(48000);
        let mut config = Config {
            preamp_db: 0.0,
            ..Default::default()
        };
        assert!(control.configure(&config));
        let ir = dsp_spatial::MatrixIr::checked(vec![[2.0, 0.0, 0.0, 2.0]]).unwrap();
        let effect = effects::PreparedSpatial::from_matrix(48000, &ir, 128).unwrap();
        assert!(control.effects.install(Box::new(effect)).is_ok());
        control.set_playing(true);
        let mut output = vec![0.0; 10000];
        for _ in 0..5000 {
            control.pcm.as_mut().unwrap().push([0.1; 2]).unwrap();
        }
        renderer.render(&mut output);
        assert!((output[9999] - 0.2).abs() < 1.0e-6);
        config.bypass = true;
        assert!(control.configure(&config));
        for _ in 0..5000 {
            control.pcm.as_mut().unwrap().push([0.1; 2]).unwrap();
        }
        renderer.render(&mut output);
        assert!((output[9999] - 0.1).abs() < 1.0e-6);
        control.effects.collect();
    }
}
