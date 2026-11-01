#!/usr/bin/env bash
# Downloads the parts of the sound engine that are too big for the repo:
#  - FluidSynth's prebuilt Android libraries (LGPL-2.1) -> app/src/main/jniLibs + app/src/main/cpp/include
#  - GeneralUser GS soundfont (free licence, see its LICENSE.txt) -> app/src/main/assets
# Needs: gh (GitHub CLI, logged in or GH_TOKEN set), git, unzip.
set -euo pipefail
cd "$(dirname "$0")/.."
ABIS="arm64-v8a armeabi-v7a x86_64"

if [ ! -f app/src/main/jniLibs/arm64-v8a/libfluidsynth.so ]; then
  rm -rf /tmp/fs && mkdir -p /tmp/fs
  gh release download --repo FluidSynth/fluidsynth --pattern '*android*.zip' --dir /tmp/fs
  ls -la /tmp/fs
  (cd /tmp/fs && for z in *.zip; do unzip -q -o "$z"; done)
  so=$(find /tmp/fs -path '*arm64-v8a*' -name libfluidsynth.so | head -1)
  [ -n "$so" ] || { echo "error: libfluidsynth.so not found in the FluidSynth Android download"; find /tmp/fs | head -50; exit 1; }
  root=$(dirname "$(dirname "$(dirname "$so")")")
  echo "FluidSynth package root: $root"; ls "$root" "$root/lib"
  for abi in $ABIS; do
    mkdir -p app/src/main/jniLibs/$abi
    cp "$root/lib/$abi/"*.so app/src/main/jniLibs/$abi/
  done
  mkdir -p app/src/main/cpp/include
  cp -R "$root/include/"* app/src/main/cpp/include/
  ls -la app/src/main/jniLibs/arm64-v8a app/src/main/cpp/include
fi

if [ ! -f app/src/main/assets/GeneralUser-GS.sf2 ]; then
  rm -rf /tmp/gu
  git clone -q --depth 1 https://github.com/mrbumpy409/GeneralUser-GS /tmp/gu
  mkdir -p app/src/main/assets
  cp /tmp/gu/GeneralUser-GS.sf2 app/src/main/assets/
  cp /tmp/gu/documentation/LICENSE.txt app/src/main/assets/GeneralUser-GS-LICENSE.txt
  ls -la app/src/main/assets
fi
