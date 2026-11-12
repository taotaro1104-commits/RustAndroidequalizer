use dsp_core::{Config, Dsp, PreparedConfig};
const SR: f32 = 48000.0;
fn config(mix: f32, ms: f32, fb: f32) -> Config {
    Config {
        preamp_db: 0.0,
        band_count: 0,
        echo_mix: mix,
        echo_time_ms: ms,
        echo_feedback: fb,
        ..Default::default()
    }
}
fn impulse_response(c: &Config, frames: usize) -> Vec<f32> {
    let mut dsp = Dsp::new(SR, PreparedConfig::new(c, SR));
    // Let the 20 ms config crossfade and the echo slew settle first.
    for _ in 0..48000 {
        dsp.process_frame([0.0; 2]);
    }
    (0..frames)
        .map(|i| dsp.process_frame([if i == 0 { 0.5 } else { 0.0 }; 2])[0])
        .collect()
}
fn settled(c: &Config, frames: usize) -> Vec<f32> {
    let mut dsp = Dsp::new(SR, PreparedConfig::new(c, SR));
    for _ in 0..48000 {
        dsp.process_frame([0.0; 2]);
    }
    (0..frames)
        .map(|i| dsp.process_frame([if i == 0 { 0.5 } else { 0.0 }; 2])[0])
        .collect()
}
#[test]
fn repeats_arrive_at_the_set_time_and_decay_with_feedback() {
    // Create the Dsp with the echo already active so slews start settled.
    let c = config(1.0, 100.0, 0.5);
    let mut dsp = Dsp::new(SR, PreparedConfig::new(&c, SR));
    dsp.update(PreparedConfig::new(&c, SR));
    for _ in 0..96000 {
        dsp.process_frame([0.0; 2]);
    }
    let out: Vec<f32> = (0..30000)
        .map(|i| dsp.process_frame([if i == 0 { 0.5 } else { 0.0 }; 2])[0])
        .collect();
    let peak = |from: usize, to: usize| out[from..to].iter().fold(0.0_f32, |m, v| m.max(v.abs()));
    assert!(peak(1, 4700) < 1.0e-3, "no sound before the first repeat");
    let first = peak(4700, 4900);
    let second = peak(9500, 9700);
    assert!(first > 0.05, "first repeat {first}");
    assert!(second > 0.1 * first && second < first, "decay {first} -> {second}");
}
#[test]
fn zero_mix_is_transparent_and_hostile_values_stay_finite() {
    let dry = settled(&config(0.0, 300.0, 0.9), 2000);
    assert!(dry[1..].iter().all(|v| v.abs() < 1.0e-6));
    for c in [config(f32::NAN, f32::NAN, f32::NAN), config(9.0, 99999.0, 9.0), config(-1.0, -5.0, -1.0)] {
        let out = impulse_response(&c, 4000);
        assert!(out.iter().all(|v| v.is_finite() && v.abs() <= 0.999));
    }
}
#[test]
fn max_feedback_stays_bounded_for_loud_continuous_input() {
    let c = config(1.0, 20.0, 5.0);
    let mut dsp = Dsp::new(SR, PreparedConfig::new(&c, SR));
    for i in 0..SR as usize * 4 {
        let x = 0.9 * (std::f32::consts::TAU * 220.0 * i as f32 / SR).sin();
        let out = dsp.process_frame([x; 2]);
        assert!(out.iter().all(|v| v.is_finite() && v.abs() <= 0.999));
    }
}
#[test]
fn disabling_clears_the_tail_so_reenabling_does_not_replay_old_audio() {
    let on = config(1.0, 200.0, 0.8);
    let off = config(0.0, 200.0, 0.8);
    let mut dsp = Dsp::new(SR, PreparedConfig::new(&on, SR));
    for i in 0..48000 {
        dsp.process_frame([0.5 * (i as f32 * 0.05).sin(); 2]);
    }
    dsp.update(PreparedConfig::new(&off, SR));
    for _ in 0..96000 {
        dsp.process_frame([0.0; 2]);
    }
    dsp.update(PreparedConfig::new(&on, SR));
    let mut peak = 0.0_f32;
    for _ in 0..48000 {
        peak = peak.max(dsp.process_frame([0.0; 2])[0].abs());
    }
    assert_eq!(peak, 0.0, "residual echo {peak}");
}
#[test]
fn bypass_removes_echo() {
    let c = Config { bypass: true, ..config(1.0, 50.0, 0.5) };
    let out = settled(&c, 6000);
    assert!(out[1..].iter().all(|v| *v == 0.0));
}
