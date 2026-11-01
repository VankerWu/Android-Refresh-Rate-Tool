#!/usr/bin/env bash
set -euo pipefail
# Read-only cross-brand Android display diagnostics (does not change any settings).
if ! command -v adb >/dev/null 2>&1; then
  echo '找不到 adb。请先将 Android SDK 的 platform-tools 添加到 PATH。' >&2
  exit 1
fi
if ! adb get-state 2>/dev/null | grep -q device; then
  echo '没有已授权的 Android USB 调试设备（adb devices）' >&2
  exit 1
fi
ROOT="$(cd "$(dirname "$0")" && pwd)"
STAMP="$(date '+%Y%m%d_%H%M%S')"
OUT="$ROOT/refresh_diagnostic_$STAMP.txt"
{
  echo '安卓高刷工具 · 只读刷新率诊断'
  echo '采集时间：'"$(date '+%F %T')"
  echo '注意：分享前请检查设备信息中是否含有不希望公开的数据'
  echo '==== DEVICE ===='
  for key in ro.product.manufacturer ro.product.brand ro.product.model ro.build.version.release ro.build.version.sdk; do
    printf '%s=' "$key"; adb shell getprop "$key" | tr -d '\r'
  done
  echo '==== SYSTEM PREFERENCES ===='
  for key in peak_refresh_rate min_refresh_rate; do
    printf '%s=' "$key"; adb shell settings get system "$key" | tr -d '\r'
  done
  echo '==== VENDOR PREFERENCES (READ-ONLY) ===='
  for row in 'secure vivo_ultra_refresh_rate_state' 'global vivo_screen_refresh_rate_mode' 'secure miui_refresh_rate' 'secure user_refresh_rate'; do
    set -- $row
    printf '%s/%s=' "$1" "$2"; adb shell settings get "$1" "$2" | tr -d '\r'
  done
  echo '==== DISPLAY ACTIVE MODES ===='
  adb shell dumpsys display | tr -d '\r' | grep -E 'mActiveModeId|mActiveSfDisplayMode|mActiveRenderFrameRate|supportedRefreshRates|mDesiredDisplayModeSpecs' | cut -c 1-350 || true
  echo '==== VIVO RMS SERVICE PRESENCE ===='
  adb shell service list | tr -d '\r' | grep -E 'rms:|rms_statistics:' || true
} > "$OUT"
echo "完成：$OUT"
