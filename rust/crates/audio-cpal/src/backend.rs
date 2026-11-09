use cpal::{
    traits::{DeviceTrait, HostTrait, StreamTrait},
    FromSample, SampleFormat, SizedSample,
};
use dsp_core::Config;
use engine::{Controller, Renderer};
use std::{
    path::Path,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
    time::Duration,
};

#[derive(Debug)]
pub struct OutputInfo {
    pub device: String,
    pub sample_rate: u32,
    pub channels: u16,
    pub format: String,
}
pub struct CpalPlayer {
    stream: Option<cpal::Stream>,
    pub controller: Controller,
    pub info: OutputInfo,
    failed: Arc<AtomicBool>,
    callbacks: Arc<AtomicU64>,
}
pub fn output_devices() -> Result<Vec<String>, String> {
    cpal::default_host()
        .output_devices()
        .map_err(|e| e.to_string())?
        .map(|d| d.name().map_err(|e| e.to_string()))
        .collect()
}
impl CpalPlayer {
    pub fn open(path: Option<&Path>, config: &Config) -> Result<Self, String> {
        let device = cpal::default_host()
            .default_output_device()
            .ok_or("No default output device")?;
        let supported = device.default_output_config().map_err(|e| e.to_string())?;
        let format = supported.sample_format();
        let stream_config: cpal::StreamConfig = supported.into();
        let rate = stream_config.sample_rate.0;
        if !(8000..=384000).contains(&rate) || stream_config.channels == 0 {
            return Err("Unsupported output configuration".into());
        }
        let info = OutputInfo {
            device: device.name().map_err(|e| e.to_string())?,
            sample_rate: rate,
            channels: stream_config.channels,
            format: format.to_string(),
        };
        let (mut controller, renderer) = engine::create(rate);
        if !controller.configure(config) {
            return Err("Configuration queue is full".into());
        }
        if let Some(path) = path {
            controller.load_file(path)?;
        } else {
            controller.load_demo()?;
        }
        controller.wait_ready(Duration::from_secs(5))?;
        let failed = Arc::new(AtomicBool::new(false));
        let callbacks = Arc::new(AtomicU64::new(0));
        macro_rules! build {
            ($t:ty) => {
                build_stream::<$t>(
                    &device,
                    &stream_config,
                    renderer,
                    failed.clone(),
                    callbacks.clone(),
                )
            };
        }
        let stream = match format {
            SampleFormat::F32 => build!(f32),
            SampleFormat::F64 => build!(f64),
            SampleFormat::I8 => build!(i8),
            SampleFormat::I16 => build!(i16),
            SampleFormat::I32 => build!(i32),
            SampleFormat::I64 => build!(i64),
            SampleFormat::U8 => build!(u8),
            SampleFormat::U16 => build!(u16),
            SampleFormat::U32 => build!(u32),
            SampleFormat::U64 => build!(u64),
            _ => return Err(format!("Unsupported device sample format: {format}")),
        }?;
        Ok(Self {
            stream: Some(stream),
            controller,
            info,
            failed,
            callbacks,
        })
    }
    pub fn play(&self) -> Result<(), String> {
        self.controller.set_playing(true);
        if let Err(e) = self.stream.as_ref().ok_or("Stream closed")?.play() {
            self.controller.set_playing(false);
            return Err(e.to_string());
        }
        Ok(())
    }
    pub fn pause(&self) {
        self.controller.set_playing(false);
    }
    pub fn failed(&self) -> bool {
        self.failed.load(Ordering::Acquire)
    }
    pub fn callbacks(&self) -> u64 {
        self.callbacks.load(Ordering::Relaxed)
    }
}
impl Drop for CpalPlayer {
    fn drop(&mut self) {
        self.controller.set_playing(false);
        // Drop the callback owner before Controller cancels and joins its worker.
        if let Some(stream) = self.stream.take() {
            let _ = stream.pause();
            drop(stream);
        }
    }
}
fn build_stream<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    mut renderer: Renderer,
    failed: Arc<AtomicBool>,
    callbacks: Arc<AtomicU64>,
) -> Result<cpal::Stream, String>
where
    T: SizedSample + FromSample<f32>,
{
    let channels = config.channels as usize;
    let mut scratch = [0.0_f32; 2048];
    device
        .build_output_stream(
            config,
            move |output: &mut [T], _| {
                // Fixed scratch supports arbitrarily large callbacks without allocation.
                for block in output.chunks_mut(channels * 1024) {
                    let frames = block.len() / channels;
                    renderer.render(&mut scratch[..frames * 2]);
                    block.fill(T::EQUILIBRIUM);
                    for (frame, stereo) in block
                        .chunks_exact_mut(channels)
                        .zip(scratch[..frames * 2].as_chunks::<2>().0.iter())
                    {
                        if channels == 1 {
                            frame[0] = T::from_sample(0.5 * (stereo[0] + stereo[1]));
                        } else {
                            frame[0] = T::from_sample(stereo[0]);
                            frame[1] = T::from_sample(stereo[1]);
                        }
                    }
                }
                callbacks.fetch_add(1, Ordering::Relaxed);
            },
            move |_| {
                failed.store(true, Ordering::Release);
            },
            None,
        )
        .map_err(|e| e.to_string())
}
