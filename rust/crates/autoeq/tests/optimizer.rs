use autoeq::{optimize, parse_csv, Point};
use dsp_core::{biquad::Coefficients, Band};
#[test]
fn known_peak_correction_reduces_shape_error_and_reserves_headroom() {
    let peak = Coefficients::peaking(
        Band {
            frequency: 1300.0,
            gain_db: 6.0,
            q: 2.0,
        },
        48000.0,
    );
    let points: Vec<_> = (0..300)
        .map(|i| {
            let hz = 20.0 * 1000.0_f32.powf(i as f32 / 299.0);
            Point {
                hz,
                measured_db: 83.0 + 20.0 * peak.magnitude(hz, 48000.0).log10(),
                target_db: 0.0,
            }
        })
        .collect();
    let fit = optimize(&points, 1, 48000).unwrap();
    assert!(
        fit.rmse < fit.baseline_rmse * 0.15,
        "{} -> {}",
        fit.baseline_rmse,
        fit.rmse
    );
    assert!((fit.bands[0].frequency - 1300.0).abs() < 120.0);
    assert!(fit.bands[0].gain_db < -5.0);
    assert!(fit.preamp_db <= -1.0 && fit.preamp_db >= -60.0);
    assert!(fit.settings_text().contains("ON PK"));
}
#[test]
fn flat_measurement_does_not_invent_filters_or_depend_on_level() {
    let points: Vec<_> = (0..32)
        .map(|i| Point {
            hz: 20.0 * 1000.0_f32.powf(i as f32 / 31.0),
            measured_db: 93.0,
            target_db: 3.0,
        })
        .collect();
    let fit = optimize(&points, 5, 48000).unwrap();
    assert!(fit.rmse < 0.0001);
    assert!(fit.bands.iter().all(|b| b.gain_db.abs() < 0.001));
}
#[test]
fn csv_validates_order_limits_and_named_target_columns() {
    let text = "frequency,raw,error,target\n20,80,99,81\n40,80,99,82\n80,80,99,83\n160,80,99,84\n320,80,99,85\n640,80,99,86\n1280,80,99,87\n2560,80,99,88";
    let points = parse_csv(text).unwrap();
    assert_eq!(points[0].target_db, 81.0);
    assert!(parse_csv(&text.replace("40,80", "20,80")).is_err());
    assert!(parse_csv(&text.replace("40,80", "40,")).is_err());
    assert!(parse_csv(&text.replace("80,99,81", "NaN,99,81")).is_err());
    assert!(optimize(&points, 31, 48000).is_err());
    assert!(parse_csv("20,0\n40,0").is_err());
}

#[test]
fn complex_targets_are_deterministic_bounded_and_never_worse_than_flat() {
    let points: Vec<_> = (0..150)
        .map(|i| {
            let hz = 20.0 * 1000.0_f32.powf(i as f32 / 149.0);
            Point {
                hz,
                measured_db: (hz.ln() * 3.7).sin() * 5.0,
                target_db: 2.0 * (hz / 1000.0).log10(),
            }
        })
        .collect();
    let a = optimize(&points, 10, 48000).unwrap();
    let b = optimize(&points, 10, 48000).unwrap();
    assert_eq!(a.settings_text(), b.settings_text());
    assert!(a.rmse <= a.baseline_rmse);
    assert!(a
        .bands
        .iter()
        .all(|b| (20.0..=20000.0).contains(&b.frequency)
            && (-12.0..=6.0).contains(&b.gain_db)
            && (0.3..=8.0).contains(&b.q)));
}
