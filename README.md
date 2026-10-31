# Mixokeys — Android (test build)

Learn songs on piano or guitar from MIDI files, with karaoke lyrics. Everything runs on the phone; nothing is uploaded and no internet is needed.

- **Staff:** a grand staff that scrolls past a playhead. Right hand (middle C and up) on the treble staff, left hand on the bass staff, with a bar showing how long to hold each note.
- **Falling notes:** notes fall onto a keyboard sized to the song.
- **Guitar:** the chord at each moment with its fingering, the next chord, and scrolling tab for any part.
- **Lyrics:** karaoke lyrics that light up word by word. They come from the MIDI file itself (lyric events, or `.kar` karaoke files), from an `.lrc` file, or from plain text that you time by tapping along.
- **Practice:** 50/75/100 % speed (the MIDI is re-timed, so the sound doesn't warble), mute the part you're playing, drag the staff or tab to move around.

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
| `app/src/main/java/.../midi/Midi.kt` | MIDI reader (tempo map, parts, lyrics, key and time signatures) and the writer used for slowed-down / muted playback |
| `.../lyrics/Lyrics.kt` | Lyrics from MIDI/KAR events, LRC (incl. word timings), plain text and tap timing |
| `.../music/Music.kt` | Note spelling, chord recognition and fingerings, tab placement |
| `.../ui/Views.kt` | Grand staff, falling notes, chord diagrams, tab and karaoke views |
| `.../MidiPlayer.kt` | Playback with Android's built-in MIDI synthesizer |
| `.../Library.kt` | Opening files, recent songs, saved lyrics |
| `.../MainActivity.kt` | The screens |

## Credits

- Music symbols: Noto Music, SIL Open Font License 1.1.
