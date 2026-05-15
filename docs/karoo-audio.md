# Karoo audio for third-party extensions — what works, what doesn't

Working notes from getting RutaGear to make sound on Hammerhead Karoo 2
and Karoo 3. Captures dead-ends so the next person doesn't repeat them.

## TL;DR

- **`karoo.dispatch(PlayBeepPattern(tones))`** is the only audio API
  Hammerhead exposes to third-party extensions. It works on both K2
  (piezo buzzer) and K3 (internal speaker).
- Standard Android **`MediaPlayer`** plays without errors but is
  **silent** on both K2 and K3 internal hardware. Karoo's audio policy
  routes third-party `MediaPlayer` output through A2DP/AVRCP, which
  goes nowhere audible unless a BT audio sink is paired.
- **`SoundPool` / `ToneGenerator`** weren't tested but likely have the
  same problem — they share the high-level audio routing.

## The MediaPlayer dead end

Symptoms in `adb logcat`:

```
RutaGear/Sound: audio focus request -> 1 (GRANTED=true)
RutaGear/Sound: vol music=5/15 alarm=6/7 notif=5/7 a2dpOn=false speakerOn=false
RutaGear/Sound: playing preset:res/2131492864 at volume=0.8
Avrcp_ext: AudioManager Player in started state: app.rutagear
Avrcp_ext: AudioManager Active Player: app.rutagear
```

Everything looks right:

- Audio focus is granted (`requestAudioFocus -> 1`).
- All stream volumes are non-zero.
- `MediaPlayer.start()` returns cleanly, no `onError` callback.
- The system audio service acknowledges us as an active player.

But the device is silent. The `Avrcp_ext` lines are the giveaway:
that's Bluetooth A2DP/AVRCP, and `a2dpOn=false` means there's no BT
sink connected. The audio is being routed to a non-existent
destination. Karoo's audio policy decides this independent of:

- `AudioAttributes.Builder().setUsage(...)` — tried `USAGE_MEDIA`,
  `USAGE_ALARM`, `USAGE_NOTIFICATION_EVENT`,
  `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`. None change the routing.
- `setContentType(...)` — `CONTENT_TYPE_MUSIC`,
  `CONTENT_TYPE_SONIFICATION` — no effect.
- File format — tried MP3 CBR 96kbps and PCM WAV 16-bit 44.1 kHz mono.
- `requestAudioFocus()` with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` —
  granted, but routing unchanged.
- `mp.setVolume(1.0f, 1.0f)` — at the API ceiling, still silent.

The only way `MediaPlayer` audio is audible on Karoo is to pair a
Bluetooth headset / speaker, at which point the A2DP route lands
somewhere real.

## What works: `karoo.dispatch(PlayBeepPattern(...))`

```kotlin
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.PlayBeepPattern

val karoo = KarooSystemService(context).apply { connect { } }
karoo.dispatch(
    PlayBeepPattern(
        listOf(
            PlayBeepPattern.Tone(1000, 150),    // 1 kHz for 150 ms
            PlayBeepPattern.Tone(null,    60),  // 60 ms silence
            PlayBeepPattern.Tone(800,  150),
            PlayBeepPattern.Tone(null,    60),
            PlayBeepPattern.Tone(600,  250),
        )
    )
)
```

Notes:

- `dispatch` returns `false` if the `KarooSystemService` isn't
  connected — make sure `connect { }` has fired its callback before
  you dispatch.
- `Tone(frequency: Int?, durationMs: Int)`. A `null` frequency means
  silence — use these as pauses between successive tones, otherwise
  the buzzer/speaker can fuse adjacent tones into one continuous beep.
- Frequency really matters. eiRadar's reference patterns use 400–1568 Hz
  and work on many units, but on at least one K3 in the wild those
  frequencies dispatched cleanly (`dispatch -> true`) yet were
  inaudible. Bumping to **1500–2700 Hz** (closer to piezo resonance
  and well within K3 speaker response) restored audio. If in doubt,
  pick frequencies in the 1500–2700 Hz band and keep tones ≥ 200 ms
  so the buzzer has time to ring up.
- There's **no volume parameter**. The Karoo system volume slider in
  the OS controls the beeper level.

This is the same API path used by:

- Karoo's own navigation prompts and ride alerts (internal).
- [eiRadar](https://github.com/yrkan/eiradar) — third-party radar
  extension that ships four "sound sets" of beep patterns. Used as the
  working reference for our implementation.

## Why doesn't `MediaPlayer` work then?

The Karoo audio HAL is a Hammerhead-controlled gateway. System apps
(navigation, ride controller, sensor pipeline) get the speaker; third
parties get whatever the `karoo-ext` API exposes. There is currently
no `PlaySoundResource` or `PlayAudioFile` effect in `karoo-ext` — just
`PlayBeepPattern`. Until Hammerhead adds one, custom MP3/WAV playback
on the internal speaker isn't reachable from a sideloaded APK.

If MP3 playback through BT audio is good enough, the standard
`MediaPlayer` path works fine once a BT sink is paired — that's the
opt-in `use_mediaplayer` pref in the app.

## Karoo OS settings that matter

Worth confirming before debugging beep silence:

- **Settings → Sounds → Master volume** must not be muted.
- **Settings → Sounds → Beeps / Notification sounds** should be on.
  Some Karoo OS versions split this into multiple toggles.
- For K2, the piezo buzzer is the only output — there's no way to
  route to the (non-existent) speaker. K3 has a real speaker.

If `karoo.dispatch(PlayBeepPattern(...))` is being called (check
`RutaGear/Beep: ... dispatch -> true`) but you still hear nothing,
the Karoo's own sound setting is almost certainly muted.

## References

- karoo-ext SDK: https://github.com/hammerheadnav/karoo-ext
- `PlayBeepPattern` model:
  `io.hammerhead.karooext.models.PlayBeepPattern` and its nested
  `Tone(frequency: Int?, durationMs: Int)` data class.
- eiRadar's working `SoundEngine`: https://github.com/yrkan/eiradar
  → `app/src/main/kotlin/io/github/ykn/variaradarpro/engine/SoundEngine.kt`
- Hammerhead extensions FAQ:
  https://support.hammerhead.io/hc/en-us/articles/31150180125083
- Karoo OS extensions library:
  https://support.hammerhead.io/hc/en-us/articles/34676015530907
