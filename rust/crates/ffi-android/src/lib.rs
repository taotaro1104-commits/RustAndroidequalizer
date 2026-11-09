//! Small C ABI used by the Oboe/JNI bridge. No JNI work occurs in the callback.
use dsp_core::{Band, Config, SpatialMode, MAX_BANDS};
use engine::{Controller, Renderer};
use std::cell::UnsafeCell;
use std::ffi::{c_char, CStr};
use std::path::Path;
use std::sync::atomic::Ordering;
use std::sync::Mutex;
use std::time::Duration;

pub struct EqHandle {
    sample_rate: u32,
    controller: Mutex<Controller>,
    renderer: UnsafeCell<Renderer>,
    last_error: Mutex<String>,
}

#[repr(C)]
#[derive(Default)]
pub struct EqStats {
    pub rendered_frames: u64,
    pub underrun_callbacks: u64,
    pub peak: f32,
    pub playing: u32,
    pub finished: u32,
    pub duration_seconds: f64,
    pub position_seconds: f64,
}

#[no_mangle]
pub extern "C" fn eq_create(sample_rate: u32) -> *mut EqHandle {
    let (controller, renderer) = engine::create(sample_rate);
    Box::into_raw(Box::new(EqHandle {
        sample_rate: sample_rate.clamp(8000, 384000),
        controller: Mutex::new(controller),
        renderer: UnsafeCell::new(renderer),
        last_error: Mutex::new(String::new()),
    }))
}

/// # Safety
/// handle is a live result of eq_create. No callback or other API may be active.
#[no_mangle]
pub unsafe extern "C" fn eq_destroy(handle: *mut EqHandle) {
    if !handle.is_null() {
        drop(Box::from_raw(handle));
    }
}

/// # Safety
/// Handle must be live. Only one callback may call this at a time. output must
/// contain writable space for frames * 2 floats, without concurrent access.
#[no_mangle]
pub unsafe extern "C" fn eq_process(handle: *mut EqHandle, output: *mut f32, frames: u32) {
    if handle.is_null() || output.is_null() || frames == 0 {
        return;
    }
    let renderer = &mut *(*handle).renderer.get();
    renderer.render(std::slice::from_raw_parts_mut(output, frames as usize * 2));
}

/// # Safety
/// Handle must be live. values points to count readable floats for this call.
/// The packed configuration has 9 scalar values followed by 31 (Hz, dB, Q) triples.
#[no_mangle]
pub unsafe extern "C" fn eq_configure(
    handle: *mut EqHandle,
    values: *const f32,
    count: u32,
) -> bool {
    if handle.is_null() || values.is_null() || count != (9 + MAX_BANDS * 3) as u32 {
        return false;
    }
    let v = std::slice::from_raw_parts(values, count as usize);
    let config = Config {
        preamp_db: v[0],
        bypass: v[1] > 0.5,
        band_count: (v[2] as usize).min(MAX_BANDS),
        compressor: v[3] > 0.5,
        threshold_db: v[4],
        ratio: v[5],
        spatial: match v[6] as u32 {
            1 => SpatialMode::Wide,
            2 => SpatialMode::Crossfeed,
            _ => SpatialMode::Off,
        },
        width: v[7],
        crossfeed: v[8],
        bands: std::array::from_fn(|i| Band {
            frequency: v[9 + i * 3],
            gain_db: v[10 + i * 3],
            q: v[11 + i * 3],
        }),
    };
    (*handle)
        .controller
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .configure(&config)
}

/// # Safety
/// Handle must be live. path is null for demo or points to a NUL-terminated UTF-8 path.
#[no_mangle]
pub unsafe extern "C" fn eq_load(handle: *mut EqHandle, path: *const c_char) -> bool {
    eq_load_at(handle, path, 0.0)
}

/// # Safety
/// Same ownership and path requirements as eq_load. Worker/control thread only.
#[no_mangle]
pub unsafe extern "C" fn eq_load_at(
    handle: *mut EqHandle,
    path: *const c_char,
    seconds: f64,
) -> bool {
    if handle.is_null() {
        return false;
    }
    let h = &*handle;
    let mut control = h.controller.lock().unwrap_or_else(|e| e.into_inner());
    let result = if path.is_null() {
        control.load_demo_at(seconds)
    } else {
        match CStr::from_ptr(path).to_str() {
            Ok(p) => control.load_file_at(Path::new(p), seconds),
            Err(e) => Err(e.to_string()),
        }
    }
    .and_then(|_| control.wait_ready(Duration::from_secs(5)));
    match result {
        Ok(()) => true,
        Err(e) => {
            *h.last_error.lock().unwrap_or_else(|e| e.into_inner()) = e;
            false
        }
    }
}

/// # Safety
/// Handle is live. May run concurrently with eq_process, but not eq_destroy.
#[no_mangle]
pub unsafe extern "C" fn eq_set_playing(handle: *mut EqHandle, playing: bool) {
    if let Some(h) = handle.as_ref() {
        h.controller
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .set_playing(playing);
    }
}

/// # Safety
/// Handle is live; output is writable for one EqStats and not concurrently accessed.
#[no_mangle]
pub unsafe extern "C" fn eq_stats(handle: *mut EqHandle, output: *mut EqStats) {
    if handle.is_null() || output.is_null() {
        return;
    }
    let mut c = (*handle)
        .controller
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    c.effects.collect();
    let s = &c.status;
    *output = EqStats {
        rendered_frames: s.rendered_frames.load(Ordering::Relaxed),
        underrun_callbacks: s.underrun_callbacks.load(Ordering::Relaxed),
        peak: f32::from_bits(s.peak_bits.load(Ordering::Relaxed)),
        playing: s.playing.load(Ordering::Acquire) as u32,
        finished: s.finished.load(Ordering::Acquire) as u32,
        duration_seconds: c.duration_seconds,
        position_seconds: c.start_seconds
            + s.rendered_frames.load(Ordering::Relaxed) as f64 / (*handle).sample_rate as f64,
    };
}

pub type EqEffect = engine::effects::PreparedSpatial;

/// # Safety
/// CSV is NUL-terminated UTF-8; output/error are writable for their capacities.
/// Worker-only: bounded file parsing and optimization can allocate and take time.
#[no_mangle]
pub unsafe extern "C" fn eq_autoeq(
    csv: *const c_char,
    bands: u32,
    sample_rate: u32,
    output: *mut c_char,
    capacity: u32,
    error: *mut c_char,
    error_capacity: u32,
) -> bool {
    let result = if csv.is_null() {
        Err("Missing measurement CSV".to_string())
    } else {
        CStr::from_ptr(csv)
            .to_str()
            .map_err(|e| e.to_string())
            .and_then(autoeq::parse_csv)
            .and_then(|points| autoeq::optimize(&points, bands as usize, sample_rate))
            .map(|fit| fit.settings_text())
    };
    let result = result.and_then(|text| {
        if output.is_null() || text.len() >= capacity as usize {
            return Err("Output buffer is too small".into());
        }
        std::ptr::copy_nonoverlapping(text.as_ptr(), output.cast::<u8>(), text.len());
        *output.add(text.len()) = 0;
        Ok(())
    });
    if let Err(message) = result {
        if !error.is_null() && error_capacity > 0 {
            let n = message.len().min(error_capacity as usize - 1);
            std::ptr::copy_nonoverlapping(message.as_ptr(), error.cast::<u8>(), n);
            *error.add(n) = 0;
        }
        false
    } else {
        true
    }
}

/// # Safety
/// Paths are null or NUL-terminated UTF-8, error is writable for capacity bytes.
/// This function is worker-only and does not access a live audio handle.
#[no_mangle]
pub unsafe extern "C" fn eq_effect_prepare(
    sample_rate: u32,
    ir_path: *const c_char,
    sofa_path: *const c_char,
    azimuth: f32,
    wet: f32,
    quality: u32,
    error: *mut c_char,
    capacity: u32,
) -> *mut EqEffect {
    let path = |p: *const c_char| -> Result<Option<&Path>, String> {
        if p.is_null() {
            Ok(None)
        } else {
            CStr::from_ptr(p)
                .to_str()
                .map(|s| Some(Path::new(s)))
                .map_err(|e| e.to_string())
        }
    };
    let result = path(ir_path).and_then(|ir| {
        path(sofa_path)
            .and_then(|sofa| engine::effects::prepare(sample_rate, ir, sofa, azimuth, wet, quality))
    });
    match result {
        Ok(effect) => Box::into_raw(effect),
        Err(message) => {
            if !error.is_null() && capacity > 0 {
                let n = message.len().min(capacity as usize - 1);
                std::ptr::copy_nonoverlapping(message.as_ptr(), error.cast::<u8>(), n);
                *error.add(n) = 0;
            }
            std::ptr::null_mut()
        }
    }
}

/// # Safety
/// Both pointers are live. On true effect is consumed. On false caller retains it.
#[no_mangle]
pub unsafe extern "C" fn eq_effect_install(handle: *mut EqHandle, effect: *mut EqEffect) -> bool {
    if handle.is_null() || effect.is_null() || (*handle).sample_rate != (*effect).sample_rate {
        return false;
    }
    let effect = Box::from_raw(effect);
    match (*handle)
        .controller
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .effects
        .install(effect)
    {
        Ok(()) => true,
        Err(effect) => {
            let _ = Box::into_raw(effect);
            false
        }
    }
}

/// # Safety
/// effect is null or an unconsumed result of eq_effect_prepare; worker-only.
#[no_mangle]
pub unsafe extern "C" fn eq_effect_destroy(effect: *mut EqEffect) {
    if !effect.is_null() {
        drop(Box::from_raw(effect));
    }
}

/// # Safety
/// effect is a live unconsumed result of eq_effect_prepare.
#[no_mangle]
pub unsafe extern "C" fn eq_effect_latency(effect: *const EqEffect) -> u32 {
    effect.as_ref().map_or(0, |e| e.latency_frames() as u32)
}

/// # Safety
/// Handle is live; buffer is writable for capacity bytes. Returns bytes excluding NUL.
#[no_mangle]
pub unsafe extern "C" fn eq_error(
    handle: *mut EqHandle,
    buffer: *mut c_char,
    capacity: u32,
) -> u32 {
    if handle.is_null() || buffer.is_null() || capacity == 0 {
        return 0;
    }
    let h = &*handle;
    let error = h
        .controller
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .error()
        .unwrap_or_else(|| {
            h.last_error
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        });
    let length = error.len().min(capacity as usize - 1);
    std::ptr::copy_nonoverlapping(error.as_ptr(), buffer.cast::<u8>(), length);
    *buffer.add(length) = 0;
    length as u32
}
