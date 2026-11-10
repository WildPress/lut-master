#!/bin/sh
# Builds the clip the device tests open: a still street scene, flattened to look like Log footage,
# 2 s at 30 fps, tagged with Samsung's Log marker so the app detects it as a Samsung Log clip.
set -eu
cd "$(dirname "$0")/.."
ffmpeg -y -loglevel error -loop 1 -i lut-gen/ref/street.jpg -t 2 -r 30 \
  -vf "scale=360:640:force_original_aspect_ratio=increase,crop=360:640,eq=contrast=0.55:brightness=0.06:saturation=0.6" \
  -c:v libx264 -preset veryslow -crf 18 -pix_fmt yuv420p \
  -movflags use_metadata_tags -metadata com.samsung.android.logvideo=1 \
  app/src/androidTest/assets/samsung-log-clip.mp4
ls -l app/src/androidTest/assets/samsung-log-clip.mp4
