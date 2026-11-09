# Local patch on Sofar 0.4.0

Upstream: crates.io `sofar` 0.4.0, MIT OR Apache-2.0. Original source and license files are retained.

`src/hdf/super_block.rs`: change the accepted superblock version range from `0..3` to `0..=3`.
The original diagnostic and dispatcher claim support for version 3, but the exclusive range rejects it.
The generated HDF5 fixture `tests/fixtures/header-v3.h5` exercises this code path;
`tests/fixtures/synthetic.sofa` covers the complete filter import path.
`src/reader.rs`: add `native_sample_rate()` to defer SRC until after selecting the two virtual-speaker filters.
The app uses its tested Rubato 0.16 path, which flushes correctly even for very short IRs.

`src/hdf/`: replace parser assertions for unsupported external input with typed parse errors.
Winnow's assertion helper panics on these inputs in debug builds; production behavior is already a parse error.

No renderer behavior is changed. Remove local patches after equivalent upstream fixes are available.
