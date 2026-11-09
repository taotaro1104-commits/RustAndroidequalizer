//! Offline, bounded PEQ fitting. This is an independent optimizer, not a port
//! of the official AutoEq solver. No network or audio-thread work is involved.
use dsp_core::{biquad::Coefficients, Band};
use std::fmt::Write;

#[derive(Clone, Copy, Debug)]
pub struct Point {
    pub hz: f32,
    pub measured_db: f32,
    pub target_db: f32,
}
pub struct Fit {
    pub bands: Vec<Band>,
    pub preamp_db: f32,
    pub baseline_rmse: f32,
    pub rmse: f32,
}
impl Fit {
    pub fn settings_text(&self) -> String {
        let mut s = format!("# Rust EQ independent optimizer; level-normalized shape RMSE: {:.3} -> {:.3} dB\nPreamp: {:.3} dB\n", self.baseline_rmse, self.rmse, self.preamp_db);
        for (i, b) in self.bands.iter().enumerate() {
            writeln!(
                s,
                "Filter {}: ON PK Fc {:.3} Hz Gain {:.3} dB Q {:.3}",
                i + 1,
                b.frequency,
                b.gain_db,
                b.q
            )
            .unwrap();
        }
        s
    }
}

/// CSV: frequency,measured_db[,target_db]. Named columns also accept raw/target.
/// Without a target column the target is flat. Measurements must be ordered.
pub fn parse_csv(text: &str) -> Result<Vec<Point>, String> {
    if text.len() > 512 * 1024 {
        return Err("Measurement CSV must be at most 512 KB".into());
    }
    let mut points = Vec::new();
    let mut columns = None;
    let mut first = true;
    for line in text.trim_start_matches('\u{feff}').lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let fields: Vec<_> = if line.contains(',') {
            line.split(',').map(str::trim).collect()
        } else if line.contains(';') {
            line.split(';').map(str::trim).collect()
        } else {
            line.split_whitespace().collect()
        };
        if first && fields.first().is_some_and(|v| v.parse::<f32>().is_err()) {
            let names: Vec<_> = fields.iter().map(|v| v.to_ascii_lowercase()).collect();
            let index = |aliases: &[&str]| names.iter().position(|v| aliases.contains(&v.as_str()));
            columns = Some((
                index(&["frequency", "frequency_hz", "hz"]).ok_or("Missing frequency column")?,
                index(&["measured_db", "measured", "raw"])
                    .ok_or("Missing measured_db / raw column")?,
                index(&["target_db", "target"]),
            ));
            first = false;
            continue;
        }
        first = false;
        let (f, m, t) = columns.unwrap_or((0, 1, (fields.len() == 3).then_some(2)));
        if columns.is_none() && !(2..=3).contains(&fields.len()) {
            return Err("Expected 2 or 3 CSV columns".into());
        }
        let number = |i: usize| {
            fields
                .get(i)
                .ok_or("Missing CSV value")?
                .parse::<f32>()
                .map_err(|_| "Invalid CSV number")
        };
        let p = Point {
            hz: number(f)?,
            measured_db: number(m)?,
            target_db: t.map(number).transpose()?.unwrap_or(0.0),
        };
        if !p.hz.is_finite()
            || !(10.0..=24000.0).contains(&p.hz)
            || !p.measured_db.is_finite()
            || !p.target_db.is_finite()
            || p.measured_db.abs() > 200.0
            || p.target_db.abs() > 200.0
            || points.last().is_some_and(|last: &Point| last.hz >= p.hz)
        {
            return Err(
                "CSV needs finite values, increasing 10–24000 Hz frequencies and ±200 dB levels"
                    .into(),
            );
        }
        points.push(p);
        if points.len() > 8192 {
            return Err("At most 8192 measurement points are supported".into());
        }
    }
    if points.len() < 8 || points.last().unwrap().hz / points[0].hz < 4.0 {
        return Err("Provide at least 8 points spanning two octaves or more".into());
    }
    Ok(points)
}

fn response(band: Band, hz: &[f32], sample_rate: f32) -> Vec<f32> {
    let c = Coefficients::peaking(band, sample_rate);
    hz.iter()
        .map(|&f| 20.0 * c.magnitude(f, sample_rate).max(1.0e-12).log10())
        .collect()
}
fn centered_loss(residual: &[f32]) -> f32 {
    let mean = residual.iter().sum::<f32>() / residual.len() as f32;
    residual.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / residual.len() as f32
}
fn candidate_loss(base: &[f32], curve: &[f32]) -> f32 {
    let mean = base.iter().zip(curve).map(|(b, c)| b + c).sum::<f32>() / base.len() as f32;
    base.iter()
        .zip(curve)
        .map(|(b, c)| (b + c - mean).powi(2))
        .sum::<f32>()
        / base.len() as f32
}

pub fn optimize(points: &[Point], band_count: usize, sample_rate: u32) -> Result<Fit, String> {
    if !(1..=10).contains(&band_count) || !(32000..=192000).contains(&sample_rate) {
        return Err("Use 1–10 bands and a 32–192 kHz sample rate".into());
    }
    // Validate direct callers through the same bounds as the file path.
    if points.len() < 8
        || points.len() > 8192
        || points.windows(2).any(|p| p[0].hz >= p[1].hz)
        || points.iter().any(|p| {
            !p.hz.is_finite()
                || !(10.0..=24000.0).contains(&p.hz)
                || !p.measured_db.is_finite()
                || !p.target_db.is_finite()
                || p.measured_db.abs() > 200.0
                || p.target_db.abs() > 200.0
        })
    {
        return Err("Invalid measurement points".into());
    }
    let low = points[0].hz.max(20.0);
    let high = points
        .last()
        .unwrap()
        .hz
        .min(20000.0)
        .min(sample_rate as f32 * 0.45);
    if high / low < 4.0 {
        return Err("Measurement range is too narrow".into());
    }
    // A uniform logarithmic grid gives equal weight per octave, independent of
    // the original measurement's point density. Exclude endpoints outside data.
    let hz: Vec<_> = (0..192)
        .map(|i| low * (high / low).powf(i as f32 / 191.0))
        .collect();
    let mut desired = Vec::with_capacity(hz.len());
    let mut at = 0;
    for &f in &hz {
        while at + 2 < points.len() && points[at + 1].hz < f {
            at += 1;
        }
        let a = points[at];
        let b = points[at + 1];
        let mix = ((f / a.hz).ln() / (b.hz / a.hz).ln()).clamp(0.0, 1.0);
        desired.push(
            (a.target_db - a.measured_db) * (1.0 - mix) + (b.target_db - b.measured_db) * mix,
        );
    }
    let mut residual: Vec<_> = desired.iter().map(|v| -v).collect();
    let baseline_rmse = centered_loss(&residual).sqrt();
    let mut bands = Vec::new();
    let mut curves: Vec<Vec<f32>> = Vec::new();
    for _ in 0..band_count {
        let mean = residual.iter().sum::<f32>() / residual.len() as f32;
        let index = residual
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| ((*a - mean).abs()).total_cmp(&(*b - mean).abs()))
            .unwrap()
            .0;
        let mut b = Band {
            frequency: hz[index],
            gain_db: (mean - residual[index]).clamp(-12.0, 6.0),
            q: 1.4,
        };
        let mut curve = response(b, &hz, sample_rate as f32);
        if candidate_loss(&residual, &curve) > centered_loss(&residual) {
            b.gain_db = 0.0;
            curve.fill(0.0);
        }
        for (r, v) in residual.iter_mut().zip(&curve) {
            *r += v;
        }
        bands.push(b);
        curves.push(curve);
        // Coordinate descent over Fc, gain and log Q with decreasing step sizes.
        for (frequency_step, gain_step, q_step) in [
            (0.45_f32, 3.0, 0.5),
            (0.18, 1.0, 0.25),
            (0.06, 0.3, 0.10),
            (0.02, 0.1, 0.04),
        ] {
            for _ in 0..3 {
                for j in 0..bands.len() {
                    let base: Vec<_> = residual
                        .iter()
                        .zip(&curves[j])
                        .map(|(r, c)| r - c)
                        .collect();
                    let mut best = bands[j];
                    let mut best_curve = curves[j].clone();
                    let mut loss = candidate_loss(&base, &best_curve);
                    for axis in 0..3 {
                        let origin = best;
                        for direction in [-1.0, 1.0] {
                            let mut candidate = origin;
                            match axis {
                                0 => {
                                    candidate.frequency = (origin.frequency
                                        * 2.0_f32.powf(direction * frequency_step))
                                    .clamp(low, high)
                                }
                                1 => {
                                    candidate.gain_db =
                                        (origin.gain_db + direction * gain_step).clamp(-12.0, 6.0)
                                }
                                _ => {
                                    candidate.q =
                                        (origin.q * (direction * q_step).exp()).clamp(0.3, 8.0)
                                }
                            }
                            let curve = response(candidate, &hz, sample_rate as f32);
                            let next_loss = candidate_loss(&base, &curve);
                            if next_loss < loss {
                                loss = next_loss;
                                best = candidate;
                                best_curve = curve;
                            }
                        }
                    }
                    bands[j] = best;
                    curves[j] = best_curve;
                    for ((r, b), c) in residual.iter_mut().zip(&base).zip(&curves[j]) {
                        *r = b + c;
                    }
                }
            }
        }
    }
    let rmse = centered_loss(&residual).sqrt();
    // Dense grid with one dB margin; final runtime limiter remains enabled.
    let dense: Vec<_> = (0..4096)
        .map(|i| 10.0 * (sample_rate as f32 * 0.45 / 10.0).powf(i as f32 / 4095.0))
        .collect();
    let mut sum = vec![0.0_f32; dense.len()];
    for b in &bands {
        for (v, r) in sum.iter_mut().zip(response(*b, &dense, sample_rate as f32)) {
            *v += r;
        }
    }
    let peak = sum.into_iter().fold(0.0_f32, f32::max);
    if peak > 59.0 {
        return Err("Correction needs excessive headroom".into());
    }
    bands.sort_by(|a, b| a.frequency.total_cmp(&b.frequency));
    Ok(Fit {
        bands,
        preamp_db: -peak - 1.0,
        baseline_rmse,
        rmse,
    })
}
