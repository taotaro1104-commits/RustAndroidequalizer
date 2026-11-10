use dsp_core::{Config, Dsp, PreparedConfig};
fn config(amount: f32) -> Config {
    Config {
        preamp_db: 0.0,
        virtual_bass_amount: amount,
        virtual_bass_hz: 110.0,
        ..Default::default()
    }
}
fn tone(amount: f32, hz: f32) -> Vec<f32> {
    let c = config(amount);
    let mut dsp = Dsp::new(48000.0, PreparedConfig::new(&c, 48000.0));
    (0..48000)
        .map(|i| {
            dsp.process_frame([0.1 * (std::f32::consts::TAU * hz * i as f32 / 48000.0).sin(); 2])[0]
        })
        .collect()
}
fn magnitude(signal: &[f32], hz: f64) -> f64 {
    let start = 12000;
    let mut re = 0.0;
    let mut im = 0.0;
    for (i, x) in signal.iter().enumerate().skip(start) {
        let w = std::f64::consts::TAU * hz * i as f64 / 48000.0;
        re += *x as f64 * w.cos();
        im += *x as f64 * w.sin();
    }
    2.0 * (re * re + im * im).sqrt() / (signal.len() - start) as f64
}
#[test]
fn low_tone_gains_second_harmonic_and_amount_scales_it() {
    let dry = tone(0.0, 60.0);
    let low = tone(0.25, 60.0);
    let high = tone(0.75, 60.0);
    assert!(magnitude(&dry, 120.0) < 1.0e-6);
    let a = magnitude(&low, 120.0);
    let b = magnitude(&high, 120.0);
    assert!(b > 0.015, "second harmonic {b}");
    assert!((b / a - 3.0).abs() < 0.02);
    assert!((magnitude(&high, 60.0) - 0.1).abs() < 0.0001);
}
#[test]
fn high_frequencies_are_not_broadly_distorted() {
    let dry = tone(0.0, 4000.0);
    let bass = tone(1.0, 4000.0);
    let error = dry
        .iter()
        .zip(&bass)
        .skip(12000)
        .map(|(a, b)| (a - b).abs())
        .fold(0.0_f32, f32::max);
    assert!(error < 0.0001, "high frequency error {error}");
}
#[test]
fn reenabling_virtual_bass_after_silence_does_not_replay_old_audio() {
    for hz in [60.0, 110.0, 180.0] {
        let on = Config {
            band_count: 0,
            virtual_bass_hz: hz,
            ..config(1.0)
        };
        let off = Config {
            virtual_bass_amount: 0.0,
            ..on
        };
        let mut dsp = Dsp::new(48000.0, PreparedConfig::new(&on, 48000.0));
        for i in 0..48000 {
            let input = 0.5 * (std::f32::consts::TAU * 60.0 * i as f32 / 48000.0).sin();
            dsp.process_frame([input; 2]);
        }

        dsp.update(PreparedConfig::new(&off, 48000.0));
        // Complete the 20 ms crossfade and leave the disabled effect silent for a second.
        for _ in 0..48000 {
            dsp.process_frame([0.0; 2]);
        }
        assert_eq!(dsp.process_frame([0.0; 2]), [0.0; 2]);

        dsp.update(PreparedConfig::new(&on, 48000.0));
        let mut peak = 0.0_f32;
        // Cover the entire re-enable crossfade and the subsequent filter tail.
        for _ in 0..4800 {
            for sample in dsp.process_frame([0.0; 2]) {
                peak = peak.max(sample.abs());
            }
        }
        assert_eq!(
            peak, 0.0,
            "residual audio with extraction frequency {hz} Hz"
        );
    }
}
#[test]
fn virtual_bass_preserves_side_signal_and_is_removed_by_bypass() {
    let c = config(0.8);
    let dry = config(0.0);
    let mut a = Dsp::new(48000.0, PreparedConfig::new(&c, 48000.0));
    let mut b = Dsp::new(48000.0, PreparedConfig::new(&dry, 48000.0));
    for i in 0..24000 {
        let w = std::f32::consts::TAU * i as f32 / 48000.0;
        let input = [
            0.1 * (w * 60.0).sin() + 0.03 * (w * 700.0).sin(),
            0.1 * (w * 60.0).sin() + 0.02 * (w * 1100.0).sin(),
        ];
        let out = a.process_frame(input);
        let reference = b.process_frame(input);
        assert!(((out[0] - out[1]) - (reference[0] - reference[1])).abs() < 1.0e-6);
    }
    a.update(PreparedConfig::new(&Config { bypass: true, ..c }, 48000.0));
    for _ in 0..1100 {
        a.process_frame([0.1, -0.05]);
    }
    assert_eq!(a.process_frame([0.1, -0.05]), [0.1, -0.05]);
}
#[test]
fn dc_and_hostile_inputs_remain_finite_without_persistent_dc() {
    for sr in [8000.0, 44100.0, 48000.0, 192000.0] {
        let c = Config {
            virtual_bass_hz: f32::NAN,
            ..config(1.0)
        };
        let mut a = Dsp::new(sr, PreparedConfig::new(&c, sr));
        let mut total = 0.0;
        for i in 0..sr as usize {
            let x = a.process_frame([0.1; 2])[0];
            if i > sr as usize / 2 {
                total += x as f64 - 0.1;
            }
            assert!(x.is_finite() && x.abs() <= 0.999);
        }
        assert!(
            total.abs() / ((sr / 2.0) as f64) < 0.0001,
            "DC at {sr}: {total}"
        );
        for x in [f32::NAN, f32::INFINITY, -32.0, 32.0] {
            assert!(a
                .process_frame([x; 2])
                .iter()
                .all(|v| v.is_finite() && v.abs() <= 0.999));
        }
    }
}
