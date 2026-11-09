use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::{Decoder, DecoderOptions};
use symphonia::core::errors::Error;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;
use symphonia::core::units::{Time, TimeBase};

pub struct AudioFile {
    format: Box<dyn FormatReader>,
    decoder: Box<dyn Decoder>,
    track_id: u32,
    pub sample_rate: u32,
    pub duration_seconds: f64,
    pub start_seconds: f64,
    time_base: TimeBase,
    skip_frames: usize,
}

impl AudioFile {
    pub fn open(path: &Path) -> Result<Self, String> {
        let mut file = File::open(path).map_err(|e| e.to_string())?;
        preflight_wave(&mut file)?;
        let stream = MediaSourceStream::new(Box::new(file), Default::default());
        let mut hint = Hint::new();
        if let Some(ext) = path.extension().and_then(|v| v.to_str()) {
            hint.with_extension(ext);
        }
        let options = FormatOptions {
            enable_gapless: true,
            ..Default::default()
        };
        let result = symphonia::default::get_probe()
            .format(&hint, stream, &options, &MetadataOptions::default())
            .map_err(|e| e.to_string())?;
        let format = result.format;
        let track = format.default_track().ok_or("No audio track")?;
        let decoder = symphonia::default::get_codecs()
            .make(&track.codec_params, &DecoderOptions::default())
            .map_err(|e| e.to_string())?;
        let sample_rate = track
            .codec_params
            .sample_rate
            .ok_or("Unknown sample rate")?;
        if !(8000..=384000).contains(&sample_rate) {
            return Err("Unsupported source sample rate".into());
        }
        let track_id = track.id;
        let time_base = track
            .codec_params
            .time_base
            .filter(|t| t.numer > 0 && t.denom > 0)
            .unwrap_or(TimeBase::new(1, sample_rate));
        let duration_seconds = track.codec_params.n_frames.map_or(0.0, |n| {
            let t = time_base.calc_time(n);
            t.seconds as f64 + t.frac
        });
        Ok(Self {
            format,
            decoder,
            track_id,
            sample_rate,
            duration_seconds,
            start_seconds: 0.0,
            time_base,
            skip_frames: 0,
        })
    }

    pub fn seek(&mut self, seconds: f64) -> Result<(), String> {
        if !seconds.is_finite() || seconds < 0.0 {
            return Err("Seek position must be finite and nonnegative".into());
        }
        let target = if self.duration_seconds > 0.0 {
            seconds.min((self.duration_seconds - 1.0 / self.sample_rate as f64).max(0.0))
        } else {
            seconds
        };
        if target == 0.0 {
            return Ok(());
        }
        let result = self
            .format
            .seek(
                SeekMode::Accurate,
                SeekTo::Time {
                    time: Time::from(target),
                    track_id: Some(self.track_id),
                },
            )
            .map_err(|e| e.to_string())?;
        self.decoder.reset();
        let delta = self
            .time_base
            .calc_time(result.required_ts.saturating_sub(result.actual_ts));
        self.skip_frames =
            ((delta.seconds as f64 + delta.frac) * self.sample_rate as f64).round() as usize;
        self.start_seconds = target;
        Ok(())
    }

    pub fn next_frames(&mut self) -> Result<Option<Vec<[f32; 2]>>, String> {
        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(Error::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                    return Ok(None)
                }
                Err(e) => return Err(e.to_string()),
            };
            if packet.track_id() != self.track_id {
                continue;
            }
            let decoded = self.decoder.decode(&packet).map_err(|e| e.to_string())?;
            if decoded.spec().rate != self.sample_rate {
                return Err("Mid-stream sample-rate changes are not supported".into());
            }
            let channels = decoded.spec().channels.count();
            if channels != 1 && channels != 2 {
                return Err("This release supports mono and stereo files only".into());
            }
            let mut buffer = SampleBuffer::<f32>::new(decoded.capacity() as u64, *decoded.spec());
            buffer.copy_interleaved_ref(decoded);
            let skip = self.skip_frames.min(buffer.samples().len() / channels);
            self.skip_frames -= skip;
            let frames = buffer
                .samples()
                .chunks_exact(channels)
                .skip(skip)
                .map(|s| {
                    let left = if s[0].is_finite() { s[0] } else { 0.0 };
                    let right = if channels == 1 {
                        left
                    } else if s[1].is_finite() {
                        s[1]
                    } else {
                        0.0
                    };
                    [left, right]
                })
                .collect();
            return Ok(Some(frames));
        }
    }
}

/// RIFF stores raw integer rates/alignment. Reject invalid values before the
/// demuxer constructs its timebase or divides by the declared block alignment.
fn preflight_wave(file: &mut File) -> Result<(), String> {
    let mut signature = [0u8; 12];
    let read = file.read(&mut signature).map_err(|e| e.to_string())?;
    file.rewind().map_err(|e| e.to_string())?;
    if read < 12 || &signature[..4] != b"RIFF" || &signature[8..] != b"WAVE" {
        return Ok(());
    }
    let length = file.metadata().map_err(|e| e.to_string())?.len();
    file.seek(SeekFrom::Start(12)).map_err(|e| e.to_string())?;
    for _ in 0..4096 {
        let mut header = [0u8; 8];
        file.read_exact(&mut header)
            .map_err(|_| "Truncated WAV header".to_string())?;
        let size = u32::from_le_bytes(header[4..8].try_into().unwrap()) as u64;
        let at = file.stream_position().map_err(|e| e.to_string())?;
        if size > length.saturating_sub(at) {
            return Err("Invalid WAV chunk length".into());
        }
        if &header[..4] == b"fmt " {
            if size < 16 {
                return Err("WAV format header is too short".into());
            }
            let mut fmt = [0u8; 16];
            file.read_exact(&mut fmt).map_err(|e| e.to_string())?;
            let channels = u16::from_le_bytes([fmt[2], fmt[3]]);
            let rate = u32::from_le_bytes(fmt[4..8].try_into().unwrap());
            let alignment = u16::from_le_bytes([fmt[12], fmt[13]]);
            if !(8000..=384000).contains(&rate) || !(1..=2).contains(&channels) || alignment == 0 {
                return Err("Invalid or unsupported WAV rate, channels or block alignment".into());
            }
            file.rewind().map_err(|e| e.to_string())?;
            return Ok(());
        }
        file.seek(SeekFrom::Current((size + size % 2) as i64))
            .map_err(|e| e.to_string())?;
    }
    Err("WAV format header not found within the supported chunk budget".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn zero_sample_rate_header_is_an_error_not_a_panic() {
        let source =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../tests/fixtures/seek-ramp.wav");
        let mut bytes = std::fs::read(source).unwrap();
        bytes[24..28].fill(0);
        let path =
            std::env::temp_dir().join(format!("rusteq-zero-rate-{}.wav", std::process::id()));
        std::fs::write(&path, bytes).unwrap();
        assert!(AudioFile::open(&path).is_err());
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn wav_seek_discards_preroll_to_requested_sample() {
        let path =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../tests/fixtures/seek-ramp.wav");
        for seconds in [0.0, 0.037, 0.375, 0.999] {
            let mut file = AudioFile::open(&path).unwrap();
            assert!((file.duration_seconds - 1.0).abs() < 1.0e-9);
            file.seek(seconds).unwrap();
            let mut decoded = Vec::new();
            while let Some(frames) = file.next_frames().unwrap() {
                decoded.extend(frames);
            }
            let first = (seconds * 44100.0).floor() as usize;
            assert_eq!(decoded.len(), 44100 - first);
            let expected = ((first % 1000) as f32 * 5.0 - 2500.0) / 32768.0;
            assert!((decoded[0][0] - expected).abs() < 1.0e-7);
        }
    }
}
