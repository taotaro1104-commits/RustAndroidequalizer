//! Worker-thread SRC. FFT plans and Vec allocations never reach the audio callback.
use rubato::{FftFixedIn, Resampler};

pub struct StereoResampler {
    inner: Option<FftFixedIn<f32>>,
    input: [Vec<f32>; 2],
    used: usize,
    skip: usize,
    input_frames: u64,
    output_frames: u64,
    input_rate: u32,
    output_rate: u32,
}

impl StereoResampler {
    pub fn new(input_rate: u32, output_rate: u32) -> Result<Self, String> {
        if !(8000..=384000).contains(&input_rate) || !(8000..=384000).contains(&output_rate) {
            return Err("Unsupported sample rate (8–384 kHz required)".into());
        }
        let inner = if input_rate == output_rate {
            None
        } else {
            Some(
                FftFixedIn::new(input_rate as usize, output_rate as usize, 1024, 2, 2)
                    .map_err(|e| e.to_string())?,
            )
        };
        let size = inner.as_ref().map_or(1024, |r| r.input_frames_next());
        let skip = inner.as_ref().map_or(0, |r| r.output_delay());
        Ok(Self {
            inner,
            input: [vec![0.0; size], vec![0.0; size]],
            used: 0,
            skip,
            input_frames: 0,
            output_frames: 0,
            input_rate,
            output_rate,
        })
    }

    pub fn push(&mut self, frames: &[[f32; 2]]) -> Result<Vec<[f32; 2]>, String> {
        self.input_frames += frames.len() as u64;
        if self.inner.is_none() {
            self.output_frames += frames.len() as u64;
            return Ok(frames.to_vec());
        }
        let mut result = Vec::new();
        for frame in frames {
            self.input[0][self.used] = frame[0];
            self.input[1][self.used] = frame[1];
            self.used += 1;
            if self.used == self.input[0].len() {
                self.run(&mut result)?;
                self.used = 0;
            }
        }
        Ok(result)
    }

    fn expected_frames(&self) -> u64 {
        (self.input_frames * self.output_rate as u64 + self.input_rate as u64 / 2)
            / self.input_rate as u64
    }

    fn run(&mut self, output: &mut Vec<[f32; 2]>) -> Result<(), String> {
        let data = self
            .inner
            .as_mut()
            .ok_or("No resampler")?
            .process(&self.input, None)
            .map_err(|e| e.to_string())?;
        for (&left, &right) in data[0].iter().zip(&data[1]) {
            if self.skip > 0 {
                self.skip -= 1;
                continue;
            }
            if self.output_frames < self.expected_frames() {
                output.push([left, right]);
                self.output_frames += 1;
            }
        }
        Ok(())
    }

    /// Flush FFT delay and trim zero padding to the exact rounded output duration.
    pub fn finish(&mut self) -> Result<Vec<[f32; 2]>, String> {
        let mut output = Vec::new();
        if self.inner.is_some() {
            self.input[0][self.used..].fill(0.0);
            self.input[1][self.used..].fill(0.0);
            self.run(&mut output)?;
            self.input[0].fill(0.0);
            self.input[1].fill(0.0);
            while self.output_frames < self.expected_frames() {
                self.run(&mut output)?;
            }
            self.used = 0;
        }
        Ok(output)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sample_count_and_block_boundaries_are_invariant() {
        let input: Vec<_> = (0..44117)
            .map(|i| {
                let v = (i as f32 * 0.03).sin() * 0.3;
                [v, -v]
            })
            .collect();
        let convert = |block| {
            let mut r = StereoResampler::new(44100, 48000).unwrap();
            let mut out = Vec::new();
            for chunk in input.chunks(block) {
                out.extend(r.push(chunk).unwrap());
            }
            out.extend(r.finish().unwrap());
            out
        };
        let a = convert(97);
        let b = convert(4096);
        assert_eq!(a.len(), (44117u64 * 48000 + 22050) as usize / 44100);
        assert_eq!(a, b);
        assert!(a.iter().flatten().all(|x| x.is_finite()));
    }

    #[test]
    fn native_rate_is_bit_exact() {
        let mut r = StereoResampler::new(48000, 48000).unwrap();
        let input = [[0.25, -0.5], [0.0, 1.0]];
        assert_eq!(r.push(&input).unwrap(), input);
        assert!(r.finish().unwrap().is_empty());
    }
}
