# Cassette size detection on Karoo — what the SDK gives you and what it doesn't

Notes from getting RutaGear to know how many cogs your cassette has so
it can fire "granny gear" on the right shift instead of a guess.

## The ideal: `SHIFTING_REAR_GEAR_MAX`

karoo-ext declares an optional field on the `SHIFTING_REAR_GEAR`
DataType:

```kotlin
DataType.Type.SHIFTING_REAR_GEAR     // "TYPE_SHIFTING_REAR_GEAR_ID"
DataType.Field.SHIFTING_REAR_GEAR_MAX // "FIELD_SHIFTING_REAR_GEAR_MAX_ID"
```

When populated, you read it inline from each shift event:

```kotlin
karoo.streamDataFlow(DataType.Type.SHIFTING_REAR_GEAR).collect { state ->
    if (state !is StreamState.Streaming) return@collect
    val values = state.dataPoint.values
    val gear = state.dataPoint.singleValue?.toInt() ?: return@collect
    val max  = values[DataType.Field.SHIFTING_REAR_GEAR_MAX]?.toInt()
    // ...
}
```

## What actually happens in 2026

Field is *declared* in the SDK but **not populated** for at least the
SRAM AXS pairings we've tested on Karoo 3 firmware as of 2026-05.
`values` only contains the current gear; the `SHIFTING_REAR_GEAR_MAX`
key is absent on every shift event.

Real ride log from a `GIANT TCR ADVANCED PRO 1` profile with SRAM AXS
paired (`adb logcat -i RutaGear`):

```
RutaGear: gear 7 -> 9   (max=12 granny=true small=true)
RutaGear: gear 9 -> 10  (max=12 granny=true small=true)
RutaGear: gear 10 -> 11 (max=12 granny=true small=true)
RutaGear: gear 11 -> 12 (max=12 granny=true small=true)
RutaGear: GRANNY cog trigger — uri='' useMP=false
```

The `max=12` is from our own prefs fallback. There's no
`REAR_GEAR_MAX -> N (from field)` line anywhere — meaning the field
never appeared in `values`, even though the AXS system is fully paired
and the gear stream is flowing correctly.

The asymmetry is frustrating: Karoo's *own* built-in gearing data
field draws a cassette graphic with the right number of cogs, so the
firmware **does** know the cassette size — it just doesn't expose it
to third-party extensions via the documented field.

## The workaround: auto-learn from the gear stream

The `singleValue` we get back is the current gear *index* (1-indexed
from the smallest cog). Across a ride the rider eventually shifts to
their largest cog, which means the highest gear index ever observed
**is** the cassette size.

Implementation in `RutagearExtension.observeGear()`:

```kotlin
if (profileId != null) {
    CogPrefs.bumpLearned(prefs, profileId, gear)
}
```

`bumpLearned` only writes when `gear > previous high`. Cost is one
SharedPreferences read per shift, one write the few times a new high
is hit. The learned value is keyed by `cog_count_learned:<profileId>`
so different bikes (each with a different Karoo ride profile) build
up their own value independently.

## Resolution order at trigger time

`CogPrefs.resolve(prefs, profileId)`:

1. **observed for this profile** — `cog_count_observed:<id>`, written
   only when `SHIFTING_REAR_GEAR_MAX` is actually populated (rare in
   the wild but still preferred when present).
2. **learned for this profile** — `cog_count_learned:<id>`, the
   highest gear ever seen on this bike. Activates after the first ride
   that hits the granny cog.
3. **manual for this profile** — `cog_count:<id>`, what the user typed
   in the EditText.
4. **legacy global** — `cog_count`, the pre-0.2.0 single value, kept
   for migration of existing installs.
5. **12** — modern SRAM 12-speed default.

## Operational expectations

- **First ride on a new bike**: `manual` value drives the granny
  trigger. If the rider hits their actual granny cog during the ride,
  `learned` catches up automatically and from that point on the manual
  setting is irrelevant.
- **Subsequent rides on the same bike**: `learned` is already set, so
  granny fires correctly even before the first shift of the ride.
- **Switching bikes mid-trip**: Karoo's ActiveRideProfile event fires
  on profile change, and we re-resolve against the new profile id's
  keys — no app restart needed.
- **A rider who never uses their granny gear**: `learned` will lock in
  to the *second-largest* cog they actually use. That's an under-count
  and the granny trigger will fire one cog too early. The fix in that
  case is to set the manual value once — but realistically, the kind
  of rider who never granny-shifts won't want a granny sound anyway.

## Chain-line protection (AXS small-ring corner case)

SRAM AXS will not shift the rear into the smallest cog (often 10T)
while the chain is on the small front ring — chain-line protection
prevents extreme cross-chaining. A naive `gear == 1` small-cog
trigger therefore never fires on the small chainring.

Fix mirrors the max-learning, but for the bottom of the cassette and
keyed by front gear:

- Subscribe to `SHIFTING_FRONT_GEAR` in a second coroutine, store the
  current chainring index in `@Volatile var activeFrontGear`.
- `CogPrefs.bumpLearnedMin(prefs, profileId, front, gear)` writes a
  per-(profile, front) high-water mark for the lowest gear ever seen,
  keyed `cog_min_learned:<profileId>:front<N>`.
- The small-cog trigger fires when `gear == CogPrefs.minLearned(prefs, profileId, activeFrontGear)`
  instead of the literal `1`. Default falls back to 1 until learning
  has data.

Net behaviour: on the big ring the trigger learns `min=1` and fires
there; on the small ring it learns `min=3` (or whatever AXS allows)
and fires there. No per-bike config needed.

## When to revisit

If Hammerhead starts populating `SHIFTING_REAR_GEAR_MAX` for real, the
existing code already handles it: the `observed` value takes
precedence over `learned`. No change needed. Until then, learning from
the gear stream is the only reliable path.

## References

- `app/src/main/kotlin/app/rutagear/CogPrefs.kt` — pref schema + resolver
- `app/src/main/kotlin/app/rutagear/RutagearExtension.kt` —
  `observeGear()` does the bumpLearned call on every shift
- karoo-ext SDK: https://github.com/hammerheadnav/karoo-ext (DataType.kt
  declares both `Type.SHIFTING_REAR_GEAR` and
  `Field.SHIFTING_REAR_GEAR_MAX`)
