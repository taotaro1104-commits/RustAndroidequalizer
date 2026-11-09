"""Generate owned, artificial SOFA test data. Requires numpy and h5py; never runtime."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "artifacts/python-hdf"))
import h5py
import numpy as np

destination = root / "tests/fixtures/synthetic.sofa"
destination.parent.mkdir(parents=True, exist_ok=True)
with h5py.File(destination, "w", libver="v108", track_order=True) as f:
    for name, value in {
        "Conventions": "SOFA", "SOFAConventions": "SimpleFreeFieldHRIR", "DataType": "FIR",
        "Version": "1.0", "SOFAConventionsVersion": "1.0", "License": "CC0-1.0",
        "Comment": "Artificial impulse fixture, not measured HRTF data; for software tests only",
    }.items():
        f.attrs[name] = np.bytes_(value)
    for name, size in {"I": 1, "C": 3, "R": 2, "E": 1, "N": 16, "M": 6}.items():
        f.create_dataset(name, data=np.arange(size, dtype=np.float64))

    def array(name, data, dimensions, **attributes):
        d = f.create_dataset(name, data=np.asarray(data, dtype=np.float64))
        d.attrs["DIMENSION_LIST"] = np.bytes_(dimensions)
        for k, v in attributes.items():
            d.attrs[k] = np.bytes_(v)

    source = np.array([[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]])
    array("SourcePosition", source, "M,C", Type="cartesian", Units="metre")
    array("ListenerPosition", [[0, 0, 0]], "I,C", Type="cartesian", Units="metre")
    array("ListenerView", [[1, 0, 0]], "I,C", Type="cartesian", Units="metre")
    array("ListenerUp", [[0, 0, 1]], "I,C", Type="cartesian", Units="metre")
    array("ReceiverPosition", [[[0], [.09], [0]], [[0], [-.09], [0]]], "R,C,I", Type="cartesian", Units="metre")
    array("EmitterPosition", [[[0], [0], [0]]], "E,C,I", Type="cartesian", Units="metre")
    ir = np.zeros((6, 2, 16))
    for i, p in enumerate(source):
        ir[i, 0, 0] = .6 + .2*p[1]
        ir[i, 1, 0] = .6 - .2*p[1]
        ir[i, :, 3] = [.08, -.06]
    array("Data.IR", ir, "M,R,N")
    array("Data.SamplingRate", [44100], "I", Units="hertz")
    array("Data.Delay", [[0, .5]], "I,R", Units="sample")

android = root / "android/app/src/androidTest/assets/synthetic.sofa"
android.parent.mkdir(parents=True, exist_ok=True)
android.write_bytes(destination.read_bytes())
print(destination, destination.stat().st_size)

# Legal HDF5 v3 root header, exercising the upstream version-range fix.
with h5py.File(root / "tests/fixtures/header-v3.h5", "w", libver="v110", track_order=True) as f:
    f.attrs["Conventions"] = np.bytes_("SOFA")

import wave
import struct
wav_path = root / "tests/fixtures/seek-ramp.wav"
with wave.open(str(wav_path), "wb") as f:
    f.setnchannels(1); f.setsampwidth(2); f.setframerate(44100)
    f.writeframes(b"".join(struct.pack("<h", (i % 1000)*5-2500) for i in range(44100)))
(root / "android/app/src/androidTest/assets/seek-ramp.wav").write_bytes(wav_path.read_bytes())
