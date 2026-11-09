//! Desktop audio I/O; Android continues to use the dedicated Oboe backend.
#[cfg(feature = "cpal-backend")]
mod backend;
#[cfg(feature = "cpal-backend")]
pub use backend::*;
