use dsp_spatial::{Convolver, MatrixIr};

#[test]
fn long_scheduled_tail_matches_direct_matrix_convolution_and_latency() {
    let taps: Vec<_> = (0..9001)
        .map(|i| {
            let v = (i as f32 * 0.37).sin() * 0.003;
            [v, v * 0.3, -v * 0.2, v * 0.8]
        })
        .collect();
    let input: Vec<_> = (0..97)
        .map(|i| [(i as f32 * 0.13).sin(), (i as f32 * 0.27).cos()])
        .collect();
    let mut reference = vec![[0.0_f64; 2]; taps.len() + input.len() - 1];
    for (i, x) in input.iter().enumerate() {
        for (k, h) in taps.iter().enumerate() {
            for ear in 0..2 {
                reference[i + k][ear] +=
                    x[0] as f64 * h[ear * 2] as f64 + x[1] as f64 * h[ear * 2 + 1] as f64;
            }
        }
    }
    for partition in [64, 128, 256, 512, 1024] {
        let mut c = Convolver::new(&MatrixIr::checked(taps.clone()).unwrap(), partition).unwrap();
        assert_eq!(c.latency_frames(), partition);
        assert_eq!(c.tail_frames(), partition + taps.len() - 1);
        for n in 0..reference.len() + partition + 32 {
            let out = c.process_frame(input.get(n).copied().unwrap_or([0.0; 2]));
            for ear in 0..2 {
                let expected = n
                    .checked_sub(partition)
                    .and_then(|i| reference.get(i))
                    .map_or(0.0, |v| v[ear]);
                assert!(
                    (out[ear] as f64 - expected).abs() < 1.0e-5,
                    "partition={partition} frame={n} ear={ear}: {} vs {expected}",
                    out[ear]
                );
            }
        }
    }
}

#[test]
fn hdf_v3_header_and_invalid_input_return_results_without_panics() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../tests/fixtures");
    let bytes = std::fs::read(root.join("header-v3.h5")).unwrap();
    assert_eq!(bytes[8], 3);
    assert!(sofar::hdf::parse(&bytes).is_ok());
    for length in [0, 1, 8, 9, 16, 32] {
        assert!(sofar::hdf::parse(&bytes[..length]).is_err());
    }
}

#[test]
fn sofa_fixture_loads_interpolates_and_resamples() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../tests/fixtures/synthetic.sofa");
    for rate in [44100, 48000, 96000] {
        let ir = dsp_spatial::hrtf::from_sofa(&path, rate, 30.0).unwrap();
        assert!(ir.taps.iter().flatten().all(|v| v.is_finite()));
        assert!(ir
            .taps
            .iter()
            .any(|v| v[0].abs() > 0.01 && v[3].abs() > 0.01));
    }
}

#[test]
fn impulse_reproduces_all_matrix_paths_after_exact_partition_latency() {
    let taps: Vec<_> = (0..385)
        .map(|i| [i as f32 * 0.0001, -0.05, 0.1, 0.2])
        .collect();
    for partition in [64, 128, 256, 512] {
        for source in 0..2 {
            let mut convolver =
                Convolver::new(&MatrixIr::checked(taps.clone()).unwrap(), partition).unwrap();
            for frame in 0..partition + taps.len() + 32 {
                let mut input = [0.0; 2];
                if frame == 0 {
                    input[source] = 1.0;
                }
                let out = convolver.process_frame(input);
                for ear in 0..2 {
                    let expected = frame
                        .checked_sub(partition)
                        .and_then(|i| taps.get(i))
                        .map_or(0.0, |v| v[ear * 2 + source]);
                    assert!(
                        (out[ear] - expected).abs() < 2.0e-6,
                        "{partition}/{source}/{ear}/{frame}: {} vs {expected}",
                        out[ear]
                    );
                }
            }
        }
    }
}

#[test]
fn streaming_matches_direct_convolution_for_variable_callbacks() {
    let input: Vec<_> = (0..1536)
        .map(|i| [(i as f32 * 0.17).sin() * 0.2, (i as f32 * 0.07).cos() * 0.1])
        .collect();
    let taps: Vec<_> = (0..333)
        .map(|i| {
            let a = (i as f32 * 0.31).sin() * 0.01;
            [a, a * 0.3, -a * 0.2, a * 0.7]
        })
        .collect();
    for block in [1, 96, 160, 192, 256, 512] {
        let mut c = Convolver::new(&MatrixIr::checked(taps.clone()).unwrap(), 128).unwrap();
        let stream: Vec<_> = input
            .iter()
            .copied()
            .chain(std::iter::repeat_n([0.0; 2], 512))
            .collect();
        let out: Vec<_> = stream
            .chunks(block)
            .flat_map(|chunk| {
                chunk
                    .iter()
                    .map(|x| c.process_frame(*x))
                    .collect::<Vec<_>>()
            })
            .collect();
        for (n, out) in out.iter().enumerate() {
            for ear in 0..2 {
                let mut expected = 0.0;
                if n >= 128 {
                    for (k, t) in taps.iter().enumerate() {
                        if let Some(i) = (n - 128).checked_sub(k).and_then(|i| input.get(i)) {
                            expected += i[0] * t[ear * 2] + i[1] * t[ear * 2 + 1];
                        }
                    }
                }
                assert!((out[ear] - expected).abs() < 5.0e-6);
            }
        }
    }
}

#[test]
fn hrtf_preserves_fractional_delays_and_stereo_source_separation() {
    let mut left = sofar::reader::Filter::new(1);
    let mut right = sofar::reader::Filter::new(1);
    left.left[0] = 1.0;
    left.right[0] = 0.25;
    left.rdelay = 1.5 / 48000.0;
    right.right[0] = 1.0;
    right.left[0] = 0.25;
    right.ldelay = 1.5 / 48000.0;
    let ir = dsp_spatial::hrtf::from_filters(&left, &right, 48000).unwrap();
    assert_eq!(ir.taps[0][0], 1.0);
    assert_eq!(ir.taps[0][3], 1.0);
    assert!((ir.taps[1][1] - 0.125).abs() < 1.0e-6);
    assert!((ir.taps[2][2] - 0.125).abs() < 1.0e-6);
    assert_eq!(ir.taps[0][1], 0.0);
}

#[test]
fn composed_room_matches_cascaded_matrices_without_extra_partition_delay() {
    let h = MatrixIr::checked(vec![[1.0, 0.2, 0.3, 0.8], [0.1, 0.0, 0.0, -0.1]]).unwrap();
    let room = MatrixIr::stereo(&[[1.0, 0.5], [0.2, 0.1]], 0.5).unwrap();
    let result = h.then_stereo(&room).unwrap();
    assert!((result.taps[0][0] - 1.0).abs() < 1.0e-6);
    assert!((result.taps[0][3] - 0.6).abs() < 1.0e-6);
    assert!((result.taps[2][0] - 0.01).abs() < 1.0e-6);
}
