#!/usr/bin/env bash
# Regenerate the two built-in fallback tones in app/src/main/res/raw/.
# WAV (PCM 16-bit 44.1 kHz mono) — Karoo's Android audio stack decodes
# WAV most reliably. MP3 VBR / weird ID3 tags decode-but-play-silent
# on Karoo in some configs. Files are ~53 KB each, well under any
# resource size threshold.
#
# granny_default.wav   : descending 880 Hz -> 440 Hz, ~600 ms
# small_cog_default.wav: ascending  440 Hz -> 880 Hz, ~600 ms

set -euo pipefail

cd "$(dirname "$0")/.."
out="app/src/main/res/raw"
mkdir -p "$out"

gen() {
  local f1="$1" f2="$2" target="$3"
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "sine=frequency=${f1}:duration=0.3" \
    -f lavfi -i "sine=frequency=${f2}:duration=0.3" \
    -filter_complex \
      "[0:a]afade=t=in:st=0:d=0.02,afade=t=out:st=0.27:d=0.03[a0];\
       [1:a]afade=t=in:st=0:d=0.02,afade=t=out:st=0.25:d=0.05[a1];\
       [a0][a1]concat=n=2:v=0:a=1[out]" \
    -map "[out]" -ar 44100 -ac 1 -c:a pcm_s16le "$target"
}

gen 880 440 "$out/granny_default.wav"
gen 440 880 "$out/small_cog_default.wav"

echo "wrote:"
ls -la "$out"/granny_default.wav "$out"/small_cog_default.wav
