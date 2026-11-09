use crate::MatrixIr;
use sofar::reader::{Filter, OpenOptions};
use std::path::Path;

/// Two virtual front speakers at ±azimuth degrees, one for each stereo channel.
/// SOFA lookup, resampling and fractional per-ear delays are prepared off-thread.
pub fn from_sofa(path: &Path, sample_rate: u32, azimuth: f32) -> Result<MatrixIr, String> {
    if !azimuth.is_finite() || !(10.0..=80.0).contains(&azimuth) {
        return Err("Speaker angle must be 10–80 degrees".into());
    }
    if std::fs::metadata(path).map_err(|e| e.to_string())?.len() > 64 * 1024 * 1024 {
        return Err("SOFA files must be at most 64 MB".into());
    }
    let sofa = OpenOptions::new()
        .native_sample_rate()
        .open(path)
        .map_err(|e| e.to_string())?;
    if sofa.filter_len() == 0 || sofa.filter_len() > 8192 {
        return Err("HRTF filter length must be 1–8192 taps".into());
    }
    let angle = azimuth.to_radians();
    let mut left = Filter::new(sofa.filter_len());
    let mut right = Filter::new(sofa.filter_len());
    sofa.filter(angle.cos(), angle.sin(), 0.0, &mut left);
    sofa.filter(angle.cos(), -angle.sin(), 0.0, &mut right);
    let source_rate = sofa.sample_rate().round() as u32;
    if source_rate != sample_rate {
        resample_filter(&mut left, source_rate, sample_rate)?;
        resample_filter(&mut right, source_rate, sample_rate)?;
    }
    from_filters(&left, &right, sample_rate)
}

fn resample_filter(filter: &mut Filter, from: u32, to: u32) -> Result<(), String> {
    let input: Vec<_> = filter
        .left
        .iter()
        .zip(&filter.right)
        .map(|(&l, &r)| [l, r])
        .collect();
    let mut src = dsp_resample::StereoResampler::new(from, to)?;
    let mut output = src.push(&input)?;
    output.extend(src.finish()?);
    filter.left = output
        .iter()
        .map(|f| f[0])
        .collect::<Vec<_>>()
        .into_boxed_slice();
    filter.right = output
        .iter()
        .map(|f| f[1])
        .collect::<Vec<_>>()
        .into_boxed_slice();
    Ok(())
}

pub fn from_filters(left: &Filter, right: &Filter, sample_rate: u32) -> Result<MatrixIr, String> {
    let paths = [
        (&*left.left, left.ldelay),
        (&*right.left, right.ldelay),
        (&*left.right, left.rdelay),
        (&*right.right, right.rdelay),
    ];
    let mut channels: [Vec<f32>; 4] = std::array::from_fn(|_| Vec::new());
    for (ch, (filter, seconds)) in paths.into_iter().enumerate() {
        if !seconds.is_finite() || !(0.0..=0.05).contains(&seconds) {
            return Err("Unsupported HRTF delay (0–50 ms required)".into());
        }
        let samples = seconds * sample_rate as f32;
        let whole = samples.floor() as usize;
        let fraction = samples - whole as f32;
        let mut delayed = vec![0.0; filter.len() + whole + 1];
        for (i, tap) in filter.iter().enumerate() {
            delayed[i + whole] += tap * (1.0 - fraction);
            delayed[i + whole + 1] += tap * fraction;
        }
        channels[ch] = delayed;
    }
    let length = channels.iter().map(Vec::len).max().unwrap_or(0);
    let taps = (0..length)
        .map(|i| std::array::from_fn(|ch| channels[ch].get(i).copied().unwrap_or(0.0)))
        .collect();
    let matrix = MatrixIr::checked(taps)?;
    if matrix.taps.iter().flatten().all(|x| x.abs() < 1.0e-12) {
        return Err("SOFA lookup returned silent filters".into());
    }
    Ok(matrix)
}
