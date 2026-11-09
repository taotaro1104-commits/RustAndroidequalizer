use std::{
    path::PathBuf,
    sync::atomic::Ordering,
    time::{Duration, Instant},
};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let mut path = None;
    let mut demo = false;
    let mut seconds = None;
    let mut config = dsp_core::Config::default();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--list-devices" => {
                for (i, name) in audio_cpal::output_devices()?.iter().enumerate() {
                    println!("{i}: {name}");
                }
                return Ok(());
            }
            "--demo" => demo = true,
            "--file" => path = Some(PathBuf::from(args.next().ok_or("Missing file path")?)),
            "--seconds" => {
                let n = args.next().ok_or("Missing duration")?.parse::<u64>()?;
                if !(1..=86400).contains(&n) {
                    return Err("Duration must be 1–86400 seconds".into());
                }
                seconds = Some(n);
            }
            "--bass" => {
                let value = args.next().ok_or("Missing bass amount")?.parse::<f32>()?;
                if !value.is_finite() || !(0.0..=1.0).contains(&value) {
                    return Err("Bass must be 0–1".into());
                }
                config.virtual_bass_amount = value;
            }
            "--preamp" => {
                let value = args.next().ok_or("Missing preamp dB")?.parse::<f32>()?;
                if !value.is_finite() || !(-60.0..=12.0).contains(&value) {
                    return Err("Preamp must be -60–12 dB".into());
                }
                config.preamp_db = value;
            }
            "--help" | "-h" => {
                help();
                return Ok(());
            }
            _ => return Err(format!("Unknown option: {arg}").into()),
        }
    }
    if demo == path.is_some() {
        help();
        return Err("Choose exactly one of --demo and --file".into());
    }
    let player = audio_cpal::CpalPlayer::open(path.as_deref(), &config)?;
    println!(
        "{} · {} Hz · {} channels · {}",
        player.info.device, player.info.sample_rate, player.info.channels, player.info.format
    );
    player.play()?;
    let begin = Instant::now();
    let limit = seconds.or(if demo { Some(10) } else { None });
    loop {
        if player.failed() {
            return Err("Audio device disconnected or stream failed".into());
        }
        if let Some(e) = player.controller.error() {
            return Err(e.into());
        }
        if player.controller.status.finished.load(Ordering::Acquire)
            || limit.is_some_and(|n| begin.elapsed() >= Duration::from_secs(n))
        {
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    println!(
        "Callbacks: {} · PCM underruns: {}",
        player.callbacks(),
        player
            .controller
            .status
            .underrun_callbacks
            .load(Ordering::Relaxed)
    );
    if player.callbacks() == 0 {
        return Err("Output callback did not run".into());
    }
    Ok(())
}
fn help() {
    println!(
        "Rust EQ desktop
  --list-devices
  --demo [--seconds 10] [--bass 0.5] [--preamp -12]
  --file <audio-file> [--seconds N] [--bass 0.5]"
    );
}
