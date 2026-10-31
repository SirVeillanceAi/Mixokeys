# Mixokeys — Android (test build)

Turns any song on your phone into piano notes, entirely on the phone.

This first build answers one question: **how fast and how accurate is it on a real phone?**

1. Pick an audio file (or share one to the app from another app).
2. The piano is separated from the band with **HT-Demucs 6-stem**. Skip this with "Solo piano recording" when the file is piano alone.
3. The notes are read with the **high-resolution piano transcription** model (Kong et al.), using its original decoding.
4. You get a piano roll that follows playback (piano part or whole song), the time each step took, and a MIDI file you can share.

The two models (about 235 MB) download once, the first time you use them. After that the app works offline, and songs never leave the phone.

## Getting the app on your phone

Every push to `main` builds the app on GitHub. The newest build is always at **Releases → Latest build → `Mixokeys.apk`**.

1. On your phone, open this repo on github.com (signed in), tap **Releases**, then **Latest build**.
2. Tap `Mixokeys.apk` to download it, then open it.
3. If Android asks, allow installing from your browser ("Install unknown apps").
4. Later builds install over the top, because every build is signed with the same key.

If a build fails, GitHub opens an issue titled "Build failed: …" with the error.

## Project layout

| Path | What it does |
|---|---|
| `app/src/main/java/.../audio/AudioDecoder.kt` | Decodes MP3/M4A/WAV/FLAC/OGG with Android's MediaCodec |
| `.../audio/Resampler.kt` | Band-limited resampling (44.1 kHz for separation, 16 kHz for the piano model) |
| `.../ml/Separator.kt` | Demucs 6-stem, sliding 7.8 s windows with the original overlap cross-fade |
| `.../ml/PianoTranscriber.kt` | Piano model over 10 s windows, keeping the middle half of each (as in the reference code) |
| `.../ml/PianoPostProcess.kt` | Line-for-line port of the reference onset/offset decoding |
| `.../ml/ModelStore.kt` | One-time model downloads, resumable, with an integrity check |
| `.../midi/MidiWriter.kt` | MIDI export |
| `.../Pipeline.kt` | Runs the steps and times them |
| `.../MainActivity.kt` | The screens |

## Credits

- HT-Demucs by Meta AI Research (Défossez et al.), MIT licence. ONNX export: `adowu/htdemucs-6s-onnx`.
- High-resolution piano transcription by Q. Kong, B. Li, X. Song, Y. Wan and Y. Wang (ByteDance), CC BY 4.0. ONNX conversion: `Clement11800/pianino-model`.
- ONNX Runtime by Microsoft, MIT licence.
