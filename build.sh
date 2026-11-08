#!/bin/sh
# Plain docker only. Do NOT use docker compose here: COMPOSE_FILE on this host targets the whole estate.
set -eu
cd "$(dirname "$0")"
# Host networking: on WSL the containers' default DNS (10.255.255.254) is unreachable, so downloads stall.
docker build --network host -t lut-master-build -f Dockerfile .

# Stable debug signing key: use ./.signing/debug.keystore if present, else a Docker volume.
if [ -f .signing/debug.keystore ]; then
  set -- -v "$PWD/.signing/debug.keystore:/root/.android/debug.keystore:ro"
else
  set -- -v lutapply-android:/root/.android
fi

docker run --rm --network host -v "$PWD":/work -v lutapply-gradle:/root/.gradle "$@" -w /work lut-master-build \
  gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ./app-debug.apk
ls -l app-debug.apk
