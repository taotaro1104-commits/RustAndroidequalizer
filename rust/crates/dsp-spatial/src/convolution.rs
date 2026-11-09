use crate::MatrixIr;
use realfft::{ComplexToReal, RealFftPlanner, RealToComplex};
use rustfft::num_complex::Complex;
use std::sync::Arc;

/// Two-tier overlap-add. Late taps use a larger FFT, with MAC work spread
/// across small hops. External latency stays one small partition.
/// Plans, buffers and destruction belong to the control worker.
pub struct Convolver {
    early: Fdl,
    late: Option<Fdl>,
    late_delay: Vec<[f32; 2]>,
    delay_position: usize,
    tail: usize,
}

impl Convolver {
    pub fn new(ir: &MatrixIr, partition: usize) -> Result<Self, String> {
        if ![64, 128, 256, 512, 1024].contains(&partition)
            || ir.taps.is_empty()
            || ir.taps.len() > 768000
            || ir
                .taps
                .iter()
                .flatten()
                .any(|x| !x.is_finite() || x.abs() > 32.0)
        {
            return Err("Unsupported convolution partition or IR".into());
        }
        let large = 1024.max(partition * 2);
        let split = large * 2;
        let use_late = ir.taps.len() > split + large;
        let early = Fdl::new(
            &ir.taps[..if use_late { split } else { ir.taps.len() }],
            partition,
            None,
        );
        let late = use_late.then(|| Fdl::new(&ir.taps[split..], large, Some(partition)));
        Self::assembled(early, late, partition, ir.taps.len())
    }
    fn assembled(
        early: Fdl,
        late: Option<Fdl>,
        partition: usize,
        length: usize,
    ) -> Result<Self, String> {
        let delay = if late.is_some() { partition } else { 0 };
        Ok(Self {
            early,
            late,
            late_delay: vec![[0.0; 2]; delay],
            delay_position: 0,
            tail: partition + length - 1,
        })
    }
    pub fn latency_frames(&self) -> usize {
        self.early.partition
    }
    pub fn tail_frames(&self) -> usize {
        self.tail
    }
    #[inline]
    pub fn process_frame(&mut self, frame: [f32; 2]) -> [f32; 2] {
        let mut out = self.early.process(frame);
        if let Some(late) = &mut self.late {
            // Late stage latency = 2 * large, matching its IR offset. Add early latency.
            let delayed = self.late_delay[self.delay_position];
            self.late_delay[self.delay_position] = late.process(frame);
            self.delay_position = (self.delay_position + 1) % self.late_delay.len();
            out[0] += delayed[0];
            out[1] += delayed[1];
        }
        out
    }
}

struct Fdl {
    partition: usize,
    parts: usize,
    bins: usize,
    position: usize,
    head: usize,
    forward: Arc<dyn RealToComplex<f32>>,
    inverse: Arc<dyn ComplexToReal<f32>>,
    kernels: [Vec<Complex<f32>>; 4],
    enabled: [bool; 4],
    history: [Vec<Complex<f32>>; 2],
    input: [Vec<f32>; 2],
    work: [Vec<Complex<f32>>; 2],
    inverse_output: Vec<f32>,
    forward_scratch: Vec<Complex<f32>>,
    inverse_scratch: Vec<Complex<f32>>,
    output: Vec<[f32; 2]>,
    next_output: Vec<[f32; 2]>,
    overlap: Vec<[f32; 2]>,
    hop: Option<usize>,
    next_part: usize,
    parts_per_hop: usize,
}

impl Fdl {
    fn new(taps: &[[f32; 4]], partition: usize, hop: Option<usize>) -> Self {
        let n = partition * 2;
        let bins = partition + 1;
        let parts = taps.len().div_ceil(partition);
        let mut planner = RealFftPlanner::<f32>::new();
        let forward = planner.plan_fft_forward(n);
        let inverse = planner.plan_fft_inverse(n);
        let enabled = std::array::from_fn(|ch| taps.iter().any(|t| t[ch] != 0.0));
        let mut kernels: [Vec<Complex<f32>>; 4] = std::array::from_fn(|ch| {
            vec![Complex::default(); if enabled[ch] { bins * parts } else { 0 }]
        });
        let mut input = vec![0.0; n];
        for ch in 0..4 {
            if !enabled[ch] {
                continue;
            }
            for part in 0..parts {
                input.fill(0.0);
                for (v, tap) in input
                    .iter_mut()
                    .zip(taps.iter().skip(part * partition).take(partition))
                {
                    *v = tap[ch];
                }
                let spectrum = &mut kernels[ch][part * bins..(part + 1) * bins];
                forward
                    .process(&mut input, spectrum)
                    .expect("FFT plan sizes");
                for v in spectrum {
                    *v /= n as f32;
                }
            }
        }
        Self {
            partition,
            parts,
            bins,
            position: 0,
            head: parts - 1,
            forward_scratch: forward.make_scratch_vec(),
            inverse_scratch: inverse.make_scratch_vec(),
            forward,
            inverse,
            kernels,
            enabled,
            history: std::array::from_fn(|_| vec![Complex::default(); bins * parts]),
            input: std::array::from_fn(|_| vec![0.0; n]),
            work: std::array::from_fn(|_| vec![Complex::default(); bins]),
            inverse_output: vec![0.0; n],
            output: vec![[0.0; 2]; partition],
            next_output: vec![[0.0; 2]; partition],
            overlap: vec![[0.0; 2]; partition],
            hop,
            next_part: parts,
            parts_per_hop: parts.div_ceil(hop.map_or(1, |h| partition / h)),
        }
    }
    #[inline]
    fn process(&mut self, frame: [f32; 2]) -> [f32; 2] {
        let out = self.output[self.position];
        for (ch, value) in frame.iter().enumerate() {
            self.input[ch][self.position] = if value.is_finite() {
                value.clamp(-32.0, 32.0)
            } else {
                0.0
            };
        }
        if let Some(hop) = self.hop {
            if self.position.is_multiple_of(hop) && self.next_part < self.parts {
                let end = (self.next_part + self.parts_per_hop).min(self.parts);
                self.accumulate(self.next_part, end);
                self.next_part = end;
                if end == self.parts {
                    self.finish();
                }
            }
        }
        self.position += 1;
        if self.position == self.partition {
            if self.hop.is_some() {
                std::mem::swap(&mut self.output, &mut self.next_output);
            }
            self.begin();
            if self.hop.is_none() {
                self.accumulate(0, self.parts);
                self.finish();
                std::mem::swap(&mut self.output, &mut self.next_output);
            }
            self.position = 0;
        }
        out
    }
    fn begin(&mut self) {
        self.head = (self.head + 1) % self.parts;
        for source in 0..2 {
            self.input[source][self.partition..].fill(0.0);
            self.forward
                .process_with_scratch(
                    &mut self.input[source],
                    &mut self.history[source][self.head * self.bins..(self.head + 1) * self.bins],
                    &mut self.forward_scratch,
                )
                .expect("FFT plan sizes");
        }
        for work in &mut self.work {
            work.fill(Complex::default());
        }
        self.next_part = 0;
    }
    fn accumulate(&mut self, begin: usize, end: usize) {
        for ear in 0..2 {
            for source in 0..2 {
                let ch = ear * 2 + source;
                if !self.enabled[ch] {
                    continue;
                }
                for part in begin..end {
                    let history = (self.head + self.parts - part) % self.parts;
                    let x = &self.history[source][history * self.bins..(history + 1) * self.bins];
                    let h = &self.kernels[ch][part * self.bins..(part + 1) * self.bins];
                    for ((sum, x), h) in self.work[ear].iter_mut().zip(x).zip(h) {
                        *sum += *x * *h;
                    }
                }
            }
        }
    }
    fn finish(&mut self) {
        for ear in 0..2 {
            self.work[ear][0].im = 0.0;
            self.work[ear][self.partition].im = 0.0;
            self.inverse
                .process_with_scratch(
                    &mut self.work[ear],
                    &mut self.inverse_output,
                    &mut self.inverse_scratch,
                )
                .expect("FFT plan sizes");
            for i in 0..self.partition {
                self.next_output[i][ear] = self.inverse_output[i] + self.overlap[i][ear];
                self.overlap[i][ear] = self.inverse_output[i + self.partition];
            }
        }
    }
}
