use dsp_core::Config;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::sync::atomic::Ordering;
use std::time::Duration;

struct GuardedAllocator;
thread_local! { static RT: Cell<bool> = const { Cell::new(false) }; static ALLOCATIONS: Cell<usize> = const { Cell::new(0) }; }
unsafe impl GlobalAlloc for GuardedAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if RT.try_with(|v| v.get()).unwrap_or(false) {
            let _ = ALLOCATIONS.try_with(|n| n.set(n.get() + 1));
        }
        System.alloc(layout)
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        if RT.try_with(|v| v.get()).unwrap_or(false) {
            let _ = ALLOCATIONS.try_with(|n| n.set(n.get() + 1));
        }
        System.dealloc(ptr, layout)
    }
}
#[global_allocator]
static ALLOCATOR: GuardedAllocator = GuardedAllocator;

#[test]
fn callback_allocates_and_frees_nothing_even_during_updates() {
    let (mut control, mut renderer) = engine::create(48000);
    let mut output = [0.0; 512];
    control.set_playing(true);
    let mut config = Config {
        virtual_bass_amount: 0.75,
        echo_mix: 0.5,
        ..Default::default()
    };
    for i in 0..40 {
        config.bands[8].gain_db = (i % 12) as f32;
        assert!(control.configure(&config));
        RT.with(|v| v.set(true));
        renderer.render(&mut output);
        RT.with(|v| v.set(false));
    }
    assert_eq!(ALLOCATIONS.with(|n| n.get()), 0);
    assert!(output.iter().all(|v| v.is_finite()));
}

#[test]
fn pause_preserves_position_and_worker_cancels_when_queue_is_full() {
    let (mut control, mut renderer) = engine::create(48000);
    control.load_demo().unwrap();
    control.wait_ready(Duration::from_secs(3)).unwrap();
    let mut buffer = [0.0; 384];
    renderer.render(&mut buffer);
    assert_eq!(buffer, [0.0; 384]);
    assert_eq!(control.status.rendered_frames.load(Ordering::Relaxed), 0);
    control.set_playing(true);
    renderer.render(&mut buffer);
    assert!(buffer.iter().any(|x| x.abs() > 0.0001));
    assert_eq!(control.status.rendered_frames.load(Ordering::Relaxed), 192);
    let start = std::time::Instant::now();
    drop(control);
    assert!(start.elapsed() < Duration::from_secs(1));
}

#[test]
fn configuration_queue_reports_backpressure() {
    let (mut control, mut renderer) = engine::create(48000);
    for _ in 0..8 {
        assert!(control.configure(&Config::default()));
    }
    assert!(!control.configure(&Config::default()));
    renderer.render(&mut []);
    assert!(control.configure(&Config::default()));
}

#[test]
fn convolution_swaps_and_retirement_do_not_allocate_or_free_on_callback() {
    let (mut control, mut renderer) = engine::create(48000);
    let mut output = [0.0; 384];
    for i in 0..8 {
        control.set_playing(i % 3 != 0);
        let effect = engine::effects::prepare(
            48000,
            if i % 2 == 0 {
                Some(std::path::Path::new(""))
            } else {
                None
            },
            None,
            30.0,
            0.4,
            2,
        )
        .unwrap();
        assert!(control.effects.install(effect).is_ok());
        for _ in 0..20 {
            RT.with(|v| v.set(true));
            renderer.render(&mut output);
            RT.with(|v| v.set(false));
        }
        control.effects.collect();
    }
    assert_eq!(ALLOCATIONS.with(|n| n.get()), 0);
}

#[test]
fn eco_limits_reverb_tail_while_preserving_native_rate_bypass() {
    let room = engine::effects::prepare(48000, Some(std::path::Path::new("")), None, 30.0, 1.0, 0)
        .unwrap();
    assert!(room.tail_frames() <= 12000 + 512);
    let standard =
        engine::effects::prepare(48000, Some(std::path::Path::new("")), None, 30.0, 1.0, 1)
            .unwrap();
    assert!(standard.tail_frames() > room.tail_frames());
    assert_eq!(
        engine::effects::prepare(384000, None, None, 30.0, 0.0, 0)
            .unwrap()
            .latency_frames(),
        0
    );
}

#[test]
fn long_ir_import_preserves_three_second_tail_and_honors_lower_quality_caps() {
    let fixture = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../tests/fixtures/seek-ramp.wav");
    let mut wave = std::fs::read(fixture).unwrap();
    let data = wave[44..].repeat(3);
    wave.truncate(44);
    wave[4..8].copy_from_slice(&(36 + data.len() as u32).to_le_bytes());
    wave[40..44].copy_from_slice(&(data.len() as u32).to_le_bytes());
    wave.extend(data);
    let path = std::env::temp_dir().join(format!("rusteq-long-ir-{}.wav", std::process::id()));
    std::fs::write(&path, wave).unwrap();
    let full = engine::effects::prepare(48000, Some(&path), None, 30.0, 0.5, 2).unwrap();
    assert_eq!(full.tail_frames(), 144000 - 1 + 128);
    let standard = engine::effects::prepare(48000, Some(&path), None, 30.0, 0.5, 1).unwrap();
    assert_eq!(standard.tail_frames(), 96000 - 1 + 256);
    let eco = engine::effects::prepare(48000, Some(&path), None, 30.0, 0.5, 0).unwrap();
    assert_eq!(eco.tail_frames(), 12000 - 1 + 512);
    std::fs::remove_file(path).unwrap();
}
