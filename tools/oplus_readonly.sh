#!/bin/sh
# Read-only diagnostics. Does not modify refresh rate.
set -eu
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell service list | grep -Ei 'oplusscreenmode|display' || true
adb shell dumpsys display | grep -E 'mActiveModeId|mActiveSfDisplayMode|mActiveRenderFrameRate' || true
