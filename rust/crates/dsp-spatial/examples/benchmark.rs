//! Host benchmark only; this does not replace Android device/thermal QA.
use dsp_core::{Config, Dsp, PreparedConfig};
use dsp_spatial::{Convolver, MatrixIr};
use std::{hint::black_box, time::Instant};

fn main() {
    println!("mode,frames,partition,p95_us,p99_us,p99_period_percent");
    for frames in [96, 192, 256] {
        for (mode, partition) in [
            ("peq31", 0),
            ("hrtf_simulated", 128),
            ("room_2s", 256),
            ("combined_simulated", 512),
            ("room_8s", 128),
            ("combined_8s", 128),
        ] {
            let hrtf = || {
                MatrixIr::checked(
                    (0..512)
                        .map(|i| {
                            let tail =
                                (-(i as f32) / 100.0).exp() * (i as f32 * 0.31).cos() * 0.004;
                            [
                                if i == 0 { 0.7 } else { tail },
                                tail * 0.3,
                                tail * 0.4,
                                if i == 0 { 0.7 } else { tail },
                            ]
                        })
                        .collect(),
                )
                .unwrap()
            };
            let room = || {
                MatrixIr::stereo(
                    &(0..if mode.ends_with("8s") { 384000 } else { 96000 })
                        .map(|i| {
                            let tail = (-(i as f32) / 12000.0).exp() * 0.002;
                            [
                                if i == 0 {
                                    0.7
                                } else {
                                    tail * (i as f32 * 0.77).sin()
                                },
                                if i == 0 {
                                    0.7
                                } else {
                                    tail * (i as f32 * 0.67).cos()
                                },
                            ]
                        })
                        .collect::<Vec<_>>(),
                    0.35,
                )
                .unwrap()
            };
            let ir = match mode {
                "peq31" => None,
                "hrtf_simulated" => Some(hrtf()),
                "room_2s" | "room_8s" => Some(room()),
                _ => Some(hrtf().then_stereo(&room()).unwrap()),
            };
            let mut convolution = ir.as_ref().map(|ir| Convolver::new(ir, partition).unwrap());
            let mut config = Config {
                compressor: true,
                ..Default::default()
            };
            for (i, band) in config.bands.iter_mut().enumerate() {
                band.gain_db = (i as f32 * 0.3).sin() * 3.0;
            }
            let mut dsp = Dsp::new(48000.0, PreparedConfig::new(&config, 48000.0));
            let input: Vec<_> = (0..frames)
                .map(|i| [(i as f32 * 0.17).sin() * 0.1, (i as f32 * 0.13).cos() * 0.1])
                .collect();
            let mut times = Vec::with_capacity(400);
            for block in 0..450 {
                let begin = Instant::now();
                let mut checksum = 0.0;
                for &sample in &input {
                    let base = dsp.process_unlimited(sample);
                    let wet = convolution.as_mut().map_or(base, |c| c.process_frame(base));
                    checksum += dsp.limit_output(wet)[0];
                }
                black_box(checksum);
                if block >= 50 {
                    times.push(begin.elapsed().as_secs_f64() * 1.0e6);
                }
            }
            times.sort_by(f64::total_cmp);
            let p95 = times[379];
            let p99 = times[395];
            println!(
                "{mode},{frames},{partition},{p95:.2},{p99:.2},{:.2}",
                p99 / (frames as f64 / 48000.0 * 1.0e6) * 100.0
            );
        }
    }
}
