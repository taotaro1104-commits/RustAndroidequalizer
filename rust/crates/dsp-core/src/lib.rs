//! Platform independent stereo DSP. No allocation, locks or I/O in processing.
pub mod biquad;
pub mod dynamics;
pub mod graph;
pub mod params;
pub mod virtual_bass;

pub use graph::{Dsp, PreparedConfig};
pub use params::{Band, Config, SpatialMode, MAX_BANDS};
