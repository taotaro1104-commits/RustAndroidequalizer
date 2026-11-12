use dsp_core::dyneq::{DynEq, PreparedDynEq};
use dsp_core::exciter::{Exciter, PreparedExciter};
use dsp_core::limiter::{TruePeakLimiter, CEILING};
use dsp_core::reverb::{PreparedReverb, Reverb};
use dsp_core::{Config, Dsp, PreparedConfig};

const SR: f32 = 48000.0;

fn sine(hz: f32, amp: f32, n: usize) -> Vec<f32> {
    (0..n).map(|i| amp * (std::f32::consts::TAU * hz * i as f32 / SR).sin()).collect()
}
fn rms(x: &[f32]) -> f32 {
    (x.iter().map(|v| (*v as f64).powi(2)).sum::<f64>() / x.len() as f64).sqrt() as f32
}
fn db(x: f32) -> f32 {
    20.0 * x.max(1.0e-12).log10()
}
/// Single-frequency magnitude (Goertzel-like correlation).
fn magnitude(x: &[f32], hz: f64) -> f64 {
    let (mut re, mut im) = (0.0, 0.0);
    for (i, v) in x.iter().enumerate() {
        let w = std::f64::consts::TAU * hz * i as f64 / SR as f64;
        re += *v as f64 * w.cos();
        im += *v as f64 * w.sin();
    }
    2.0 * (re * re + im * im).sqrt() / x.len() as f64
}

// ---------- reverb ----------
#[test]
fn reverb_is_transparent_when_off_and_tail_decays_at_the_set_rt60() {
    let off = PreparedReverb::new(0.0, 1.0, 0.5, SR);
    let mut r = Reverb::new(SR);
    assert_eq!(r.process([0.3, -0.2], off), [0.3, -0.2]);

    let on = PreparedReverb::new(1.0, 1.0, 0.2, SR);
    let mut r = Reverb::new(SR);
    let mut left = Vec::new();
    for i in 0..(SR as usize * 2) {
        let input = if i == 0 { [1.0, 1.0] } else { [0.0, 0.0] };
        left.push(r.process(input, on)[0]);
    }
    let early = rms(&left[(0.2 * SR) as usize..(0.3 * SR) as usize]);
    let late = rms(&left[(1.2 * SR) as usize..(1.3 * SR) as usize]);
    assert!(early > 1.0e-4, "reverb produced no tail: {early}");
    let drop = db(early) - db(late);
    assert!((40.0..90.0).contains(&drop), "decay {drop} dB per second, expected about 60");
}

#[test]
fn reverb_tail_is_cleared_after_disabling_and_hostile_input_stays_finite() {
    let on = PreparedReverb::new(0.8, 3.0, 0.5, SR);
    let off = PreparedReverb::new(0.0, 3.0, 0.5, SR);
    let mut r = Reverb::new(SR);
    for i in 0..48000 {
        r.process([0.5 * (i as f32 * 0.05).sin(); 2], on);
    }
    for _ in 0..24000 {
        r.process([0.0; 2], off);
    }
    assert_eq!(r.process([0.0; 2], off), [0.0; 2]);
    for x in [f32::NAN, f32::INFINITY, -1.0e9, 1.0e9] {
        let out = r.process([x; 2], on);
        assert!(out.iter().all(|v| v.is_finite()));
    }
    for _ in 0..96000 {
        assert!(r.process([0.0; 2], on).iter().all(|v| v.is_finite() && v.abs() < 64.0));
    }
}

#[test]
fn reverb_extreme_settings_remain_stable() {
    for (decay, damp, sr) in [(6.0, 0.0, 44100.0), (0.2, 1.0, 192000.0), (f32::NAN, f32::NAN, 8000.0)] {
        let c = PreparedReverb::new(1.0, decay, damp, sr);
        let mut r = Reverb::new(sr);
        let mut peak = 0.0_f32;
        for i in 0..(sr as usize * 3) {
            let x = if i < 100 { 0.9 } else { 0.0 };
            peak = peak.max(r.process([x; 2], c)[0].abs());
        }
        assert!(peak.is_finite() && peak < 8.0, "peak {peak} at {sr}");
    }
}

// ---------- exciter ----------
#[test]
fn exciter_adds_harmonics_to_highs_and_leaves_lows_alone() {
    let off = PreparedExciter::new(0.0, 3500.0, SR);
    let on = PreparedExciter::new(1.0, 3500.0, SR);
    let mut e = Exciter::default();
    assert_eq!(e.process([0.1, 0.2], off), [0.1, 0.2]);

    let high = sine(5000.0, 0.5, 48000);
    let mut e = Exciter::default();
    let out: Vec<f32> = high.iter().map(|v| e.process([*v; 2], on)[0]).collect();
    let before = magnitude(&high[12000..], 10000.0);
    let after = magnitude(&out[12000..], 10000.0);
    assert!(after > before + 0.005, "2nd harmonic {before} -> {after}");
    assert!(magnitude(&out[12000..], 15000.0) > magnitude(&high[12000..], 15000.0) + 0.001, "3rd harmonic missing");

    let low = sine(200.0, 0.5, 48000);
    let mut e = Exciter::default();
    let out: Vec<f32> = low.iter().map(|v| e.process([*v; 2], on)[0]).collect();
    let err = low.iter().zip(&out).skip(12000).map(|(a, b)| (a - b).abs()).fold(0.0_f32, f32::max);
    assert!(err < 0.02, "low frequencies changed by {err}");
}

#[test]
fn exciter_has_no_persistent_dc_and_stays_finite() {
    let on = PreparedExciter::new(1.0, 2000.0, SR);
    let mut e = Exciter::default();
    let mut total = 0.0_f64;
    for i in 0..96000 {
        let x = 0.6 * (i as f32 * 0.9).sin();
        let y = e.process([x; 2], on)[0];
        assert!(y.is_finite());
        if i > 48000 {
            total += y as f64;
        }
    }
    assert!((total / 48000.0).abs() < 0.01, "mean {}", total / 48000.0);
    assert!(e.process([f32::NAN; 2], on).iter().all(|v| v.is_finite()));
}

// ---------- dynamic EQ ----------
fn run_dyneq(x: &[f32], c: PreparedDynEq) -> Vec<f32> {
    let mut d = DynEq::default();
    x.iter().map(|v| d.process([*v; 2], c)[0]).collect()
}

#[test]
fn dynamic_eq_cuts_loud_bass_only() {
    let c = PreparedDynEq::new(12.0, 150.0, -30.0, 6.0, SR);
    let loud = sine(40.0, 0.5, 48000);
    let tail = |v: &[f32]| rms(&v[24000..]);
    let cut = db(tail(&loud)) - db(tail(&run_dyneq(&loud, c)));
    assert!(cut > 4.0 && cut <= 12.5, "loud bass reduced by {cut} dB");

    let quiet = sine(40.0, 0.001, 48000); // -60 dBFS, below threshold; 40 Hz is well inside the 150 Hz band
    let change = (db(tail(&quiet)) - db(tail(&run_dyneq(&quiet, c)))).abs();
    assert!(change < 1.0, "quiet bass changed by {change} dB");

    let mid = sine(2000.0, 0.5, 48000);
    let change = (db(tail(&mid)) - db(tail(&run_dyneq(&mid, c)))).abs();
    assert!(change < 0.5, "2 kHz changed by {change} dB");

    let off = PreparedDynEq::new(0.0, 150.0, -30.0, 6.0, SR);
    assert_eq!(run_dyneq(&loud, off), loud);
}

#[test]
fn dynamic_eq_recovers_when_the_bass_gets_quieter() {
    let c = PreparedDynEq::new(12.0, 150.0, -30.0, 6.0, SR);
    let mut x = sine(60.0, 0.5, 24000);
    x.extend(sine(60.0, 0.01, 48000)); // -40 dBFS afterwards, threshold -30 dB on the band level
    let out = run_dyneq(&x, c);
    let late = db(rms(&out[60000..])) - db(rms(&x[60000..]));
    assert!(late.abs() < 1.0, "still reduced by {late} dB after the loud part");
}

// ---------- true-peak limiter ----------
fn catmull(p: [f32; 4], t: f32) -> f32 {
    0.5 * (2.0 * p[1] + (-p[0] + p[2]) * t + (2.0 * p[0] - 5.0 * p[1] + 4.0 * p[2] - p[3]) * t * t + (-p[0] + 3.0 * p[1] - 3.0 * p[2] + p[3]) * t * t * t)
}
fn true_peak(x: &[f32]) -> f32 {
    let mut peak = 0.0_f32;
    for w in x.windows(4) {
        for t in [0.0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875] {
            peak = peak.max(catmull([w[0], w[1], w[2], w[3]], t).abs());
        }
    }
    peak
}

#[test]
fn limiter_is_transparent_below_the_ceiling_apart_from_its_delay() {
    let mut l = TruePeakLimiter::new(SR);
    let lat = l.latency();
    let x = sine(997.0, 0.5, 9600);
    let y: Vec<f32> = x.iter().map(|v| l.process([*v; 2], 0.99)[0]).collect();
    for i in 0..x.len() - lat {
        assert!((y[i + lat] - x[i]).abs() < 1.0e-6, "sample {i}");
    }
    assert!(lat >= 8 && lat <= 128);
}

#[test]
fn limiter_keeps_inter_sample_peaks_under_the_ceiling() {
    // A sine at fs/4 with a 45 degree phase has sample peaks of 0.707*A but a true peak of A.
    for (hz, phase) in [(12000.0_f32, std::f32::consts::FRAC_PI_4), (11000.0, 0.9), (60.0, 0.0), (8000.0, 0.3)] {
        let mut l = TruePeakLimiter::new(SR);
        let x: Vec<f32> = (0..48000).map(|i| 1.3 * (std::f32::consts::TAU * hz * i as f32 / SR + phase).sin()).collect();
        let y: Vec<f32> = x.iter().map(|v| l.process([*v; 2], 0.9995)[0]).collect();
        let tp = true_peak(&y[2000..]);
        assert!(tp <= CEILING * 1.01, "{hz} Hz: true peak {tp} above ceiling {CEILING}");
        assert!(rms(&y[2000..]) > 0.3, "{hz} Hz: limiter ate the signal");
    }
}

#[test]
fn limiter_handles_bursts_silence_and_hostile_values() {
    let mut l = TruePeakLimiter::new(SR);
    let mut peak = 0.0_f32;
    for i in 0..48000 {
        let burst = if (i / 4800) % 2 == 0 { 3.0 } else { 0.0 };
        let y = l.process([burst * (i as f32 * 0.4).sin(); 2], 0.999);
        peak = peak.max(y[0].abs());
    }
    assert!(peak <= CEILING * 1.05, "burst peak {peak}");
    for x in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY, 1.0e30] {
        assert!(l.process([x; 2], 0.999).iter().all(|v| v.is_finite()));
    }
    for _ in 0..4800 {
        assert!(l.process([0.0; 2], 0.999).iter().all(|v| v.is_finite()));
    }
}

// ---------- whole chain ----------
#[test]
fn full_chain_with_all_new_effects_is_finite_bounded_and_off_by_default() {
    let all = Config {
        preamp_db: 0.0,
        virtual_bass_amount: 2.0,
        reverb_mix: 0.5,
        exciter_amount: 1.0,
        dyn_eq_range_db: 9.0,
        true_peak_limiter: true,
        ..Default::default()
    };
    let mut dsp = Dsp::new(SR, PreparedConfig::new(&all, SR));
    for i in 0..96000 {
        let x = 0.9 * (i as f32 * 0.013).sin() + 0.3 * (i as f32 * 0.9).sin();
        let y = dsp.process_frame([x, -x]);
        assert!(y.iter().all(|v| v.is_finite() && v.abs() <= 0.999));
    }
    // Defaults leave the new effects disabled: a quiet tone passes unchanged (flat EQ, -6 dB preamp).
    let plain = Config::default();
    let mut a = Dsp::new(SR, PreparedConfig::new(&plain, SR));
    let mut b = Dsp::new(SR, PreparedConfig::new(&Config { reverb_mix: 0.0, exciter_amount: 0.0, dyn_eq_range_db: 0.0, ..plain }, SR));
    for i in 0..4800 {
        let x = 0.2 * (i as f32 * 0.05).sin();
        assert_eq!(a.process_frame([x, x]), b.process_frame([x, x]));
    }
}
