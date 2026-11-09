use std::fs;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

#[test]
fn mono_wav_decodes_resamples_and_finishes_at_exact_duration() {
    let samples = 4410usize;
    let bytes = samples * 2;
    let mut wav = Vec::new();
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&(36u32 + bytes as u32).to_le_bytes());
    wav.extend_from_slice(b"WAVEfmt ");
    wav.extend_from_slice(&16u32.to_le_bytes());
    wav.extend_from_slice(&1u16.to_le_bytes());
    wav.extend_from_slice(&1u16.to_le_bytes());
    wav.extend_from_slice(&44100u32.to_le_bytes());
    wav.extend_from_slice(&88200u32.to_le_bytes());
    wav.extend_from_slice(&2u16.to_le_bytes());
    wav.extend_from_slice(&16u16.to_le_bytes());
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&(bytes as u32).to_le_bytes());
    for i in 0..samples {
        wav.extend_from_slice(&(((i as f32 * 0.1).sin() * 8192.0) as i16).to_le_bytes());
    }
    let path = std::env::temp_dir().join(format!("rust-eq-test-{}.wav", std::process::id()));
    fs::write(&path, wav).unwrap();
    let (mut control, mut renderer) = engine::create(48000);
    control.load_file(&path).unwrap();
    control.wait_ready(Duration::from_secs(3)).unwrap();
    control.set_playing(true);
    let mut output = [0.0; 320];
    let start = Instant::now();
    while !control.status.finished.load(Ordering::Acquire) {
        renderer.render(&mut output);
        assert!(output
            .as_chunks::<2>()
            .0
            .iter()
            .all(|p| p[0] == p[1] && p[0].is_finite()));
        assert!(start.elapsed() < Duration::from_secs(5));
        std::thread::sleep(Duration::from_millis(1));
    }
    assert_eq!(control.status.rendered_frames.load(Ordering::Relaxed), 4800);
    assert!(control.error().is_none());
    drop(control);
    fs::remove_file(path).unwrap();
}

#[test]
fn malformed_input_is_reported_on_control_thread() {
    let (mut control, _) = engine::create(48000);
    assert!(control
        .load_file(std::path::Path::new("file-that-does-not-exist.wav"))
        .is_err());
}
