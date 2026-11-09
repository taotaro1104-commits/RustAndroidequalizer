//! Prepared off-thread, allocation-free stereo matrix convolution on the callback.
pub mod convolution;
pub mod hrtf;
pub mod matrix;
pub use convolution::Convolver;
pub use matrix::MatrixIr;
