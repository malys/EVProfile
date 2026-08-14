#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
stable_manifest="$(find "$root/app/build/intermediates" -path '*stableDebug*' -name AndroidManifest.xml | head -1)"
unstable_manifest="$(find "$root/app/build/intermediates" -path '*unstableDebug*' -name AndroidManifest.xml | head -1)"

if [ -z "$stable_manifest" ] || [ -z "$unstable_manifest" ]; then
  echo "::error::Merged channel manifests were not generated."
  exit 1
fi

for permission in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.INSTALL_PACKAGES; do
  if grep -q "$permission" "$stable_manifest"; then
    echo "::error file=$stable_manifest::Stable must not declare $permission"
    exit 1
  fi
done

for permission in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE; do
  if ! grep -q "$permission" "$unstable_manifest"; then
    echo "::error file=$unstable_manifest::Unstable must declare $permission"
    exit 1
  fi
done

if [ -d "$root/app/src/main/java/com/evsuite/profile/update" ]; then
  echo "::error::Updater implementation leaked into the common source set."
  exit 1
fi

echo "Channel isolation OK: stable offline/manual, unstable network/OTA."
