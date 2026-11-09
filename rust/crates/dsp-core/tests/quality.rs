use dsp_core::biquad::Coefficients;
use dsp_core::{Band, Config, Dsp, PreparedConfig, SpatialMode};

#[test]
fn peaking_response_matches_rbj_at_center_and_dc() {
    for gain in [-24.0, -6.0, 6.0, 24.0] {
        let c = Coefficients::peaking(
            Band {
                frequency: 1000.0,
                gain_db: gain,
                q: 1.4,
            },
            48000.0,
        );
        let center_db = 20.0 * c.magnitude(1000.0, 48000.0).log10();
        assert!((center_db - gain).abs() < 0.005, "{center_db} vs {gain}");
        assert!((c.magnitude(0.0, 48000.0) - 1.0).abs() < 0.0001);
    }
}

fn render(block: usize) -> Vec<f32> {
    let mut config = Config {
        preamp_db: 0.0,
        compressor: true,
        virtual_bass_amount: 0.75,
        spatial: SpatialMode::Crossfeed,
        ..Default::default()
    };
    config.bands[14].gain_db = 9.0;
    let mut dsp = Dsp::new(48000.0, PreparedConfig::new(&config, 48000.0));
    let mut signal: Vec<f32> = (0..16000)
        .flat_map(|i| {
            let x = (i as f32 * 0.13).sin() * 0.4;
            [x, -x * 0.7]
        })
        .collect();
    for chunk in signal.chunks_mut(block * 2) {
        dsp.process_interleaved(chunk);
    }
    signal
}

#[test]
fn processing_is_independent_of_callback_size() {
    let reference = render(1);
    for frames in [96, 128, 160, 192, 240, 256, 512] {
        assert_eq!(reference, render(frames), "block {frames}");
    }
}

#[test]
fn flat_eq_is_bit_exact_below_limiter_threshold() {
    let config = Config {
        preamp_db: 0.0,
        ..Default::default()
    };
    let mut dsp = Dsp::new(48000.0, PreparedConfig::new(&config, 48000.0));
    let mut data = [0.25, -0.25, 0.0, 0.0, -0.5, 0.5];
    let before = data;
    dsp.process_interleaved(&mut data);
    assert_eq!(data, before);
    dsp.process_interleaved(&mut []);
}

#[test]
fn hostile_parameters_and_samples_stay_finite_and_bounded() {
    let mut config = Config {
        preamp_db: 12.0,
        compressor: true,
        virtual_bass_amount: 0.75,
        spatial: SpatialMode::Wide,
        width: 2.0,
        ..Default::default()
    };
    for (i, b) in config.bands.iter_mut().enumerate() {
        b.gain_db = 24.0;
        b.q = if i % 2 == 0 { 0.1 } else { 18.0 };
        if i % 3 == 0 {
            b.frequency = f32::NAN;
        }
    }
    let mut dsp = Dsp::new(48000.0, PreparedConfig::new(&config, 48000.0));
    for i in 0..48000 {
        let x = match i % 4 {
            0 => f32::NAN,
            1 => f32::INFINITY,
            2 => 32.0,
            _ => -32.0,
        };
        let y = dsp.process_frame([x, x]);
        assert!(y.iter().all(|v| v.is_finite() && v.abs() <= 0.999));
    }
}

#[test]
fn bypass_transition_is_smoothed_and_monotonic_on_dc() {
    let config = Config {
        preamp_db: -12.0,
        ..Default::default()
    };
    let mut dsp = Dsp::new(48000.0, PreparedConfig::new(&config, 48000.0));
    let mut previous = dsp.process_frame([0.1; 2])[0];
    dsp.update(PreparedConfig::new(
        &Config {
            bypass: true,
            ..config
        },
        48000.0,
    ));
    for _ in 0..1100 {
        let now = dsp.process_frame([0.1; 2])[0];
        assert!(now >= previous - 1.0e-6);
        assert!((now - previous).abs() < 0.0001);
        previous = now;
    }
    assert!((previous - 0.1).abs() < 1.0e-6);
}

#[test]
fn stereo_width_preserves_mono() {
    let plain = Config {
        preamp_db: 0.0,
        ..Default::default()
    };
    let wide = Config {
        spatial: SpatialMode::Wide,
        width: 2.0,
        ..plain
    };
    let mut a = Dsp::new(48000.0, PreparedConfig::new(&plain, 48000.0));
    let mut b = Dsp::new(48000.0, PreparedConfig::new(&wide, 48000.0));
    for i in 0..1000 {
        let frame = [(i as f32 * 0.1).sin() * 0.5; 2];
        assert_eq!(a.process_frame(frame), b.process_frame(frame));
    }
}
