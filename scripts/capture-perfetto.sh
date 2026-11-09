#!/usr/bin/env bash
set -euo pipefail
mkdir -p artifacts
adb shell perfetto -o /data/misc/perfetto-traces/rusteq.perfetto-trace -t 30s sched freq idle am wm gfx view binder_driver
adb pull /data/misc/perfetto-traces/rusteq.perfetto-trace artifacts/
adb shell dumpsys media.audio_flinger > artifacts/audio_flinger.txt

