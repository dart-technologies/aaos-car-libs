#!/usr/bin/env bash
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.

set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
drawable_dir="$repo_root/a2ui-host-carui/src/main/res/drawable-nodpi"
raw_dir="$repo_root/a2ui-host-carui/src/main/res/raw"
mkdir -p "$drawable_dir" "$raw_dir"

# Deterministic, repository-owned placeholders. They contain no third-party artwork, map data,
# voice, or branding and can therefore be regenerated during an OEM handoff.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=0x10253f:s=960x540" \
  -vf "drawgrid=w=96:h=54:t=2:c=0x365270@0.7,drawbox=x=90:y=370:w=780:h=18:c=0x5ad6c8:t=fill,drawbox=x=360:y=150:w=18:h=230:c=0xffc857:t=fill,drawbox=x=340:y=130:w=58:h=58:c=0xffc857:t=fill,drawbox=x=760:y=350:w=54:h=54:c=0x5ad6c8:t=fill" \
  -frames:v 1 "$drawable_dir/demo_route_preview.png"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=0x18314f:s=960x540" \
  -vf "drawbox=x=0:y=0:w=960:h=540:c=0x18314f:t=fill,drawbox=x=110:y=90:w=740:h=360:c=0x2f6690:t=fill,drawbox=x=180:y=145:w=600:h=250:c=0x3a7ca5:t=fill,drawbox=x=430:y=205:w=100:h=130:c=0xf4d35e:t=fill" \
  -frames:v 1 "$drawable_dir/demo_media_poster.png"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=0x32174d:s=512x512" \
  -vf "drawbox=x=48:y=48:w=416:h=416:c=0x6c3baa:t=fill,drawbox=x=112:y=112:w=288:h=288:c=0x9f5fca:t=fill,drawbox=x=192:y=160:w=128:h=192:c=0xf2cc8f:t=fill" \
  -frames:v 1 "$drawable_dir/demo_album_art.png"

# Eight seconds of synthetic motion plus a quiet tone. This is a decoder/session fixture, not a
# copy of Big Buck Bunny; the UI labels it DEMO so placeholder pixels cannot be mistaken for media.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=24:duration=8" \
  -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=8" \
  -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p \
  -c:a aac -b:a 64k -shortest -movflags +faststart "$raw_dir/demo_placeholder_video.mp4"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=330:sample_rate=44100:duration=12" \
  -af "volume=0.08" -c:a aac -b:a 64k "$raw_dir/demo_placeholder_audio.m4a"

shasum -a 256 \
  "$drawable_dir/demo_route_preview.png" \
  "$drawable_dir/demo_media_poster.png" \
  "$drawable_dir/demo_album_art.png" \
  "$raw_dir/demo_placeholder_video.mp4" \
  "$raw_dir/demo_placeholder_audio.m4a"
