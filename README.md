# RutaGear

Karoo extension that plays a short piezo beep when your electronic
rear derailleur reaches a chainring-specific extreme cog. Two
triggers, each independently enableable:

- **Granny** — biggest reachable rear cog, the "you're climbing,
  there's nothing easier" extreme. On the big chainring this is the
  smallest cog (you've shifted into your fastest gear); on the small
  chainring this is the largest cog (33T on a 10-33 cassette).
  SRAM AXS chain-line protection makes those two extremes
  chainring-dependent, and RutaGear follows that.
- **Small cog** — smallest reachable rear cog, the "end of useful
  range" extreme on the *small* chainring (typically the 11T on a
  10-33 when AXS blocks the 10T cross-chain).

Each trigger plays a distinctive `PlayBeepPattern` effect — the only
audio path Karoo exposes to third-party extensions that's reliably
audible on both Karoo 2 (piezo buzzer) and Karoo 3 (internal speaker).

## How it works

The Hammerhead Karoo SDK (`io.hammerhead:karoo-ext`) exposes:

- `DataType.Type.SHIFTING_REAR_GEAR` — current rear gear (1-indexed
  from the biggest cog, i.e. gear `1` is the 33T granny cog and
  gear `cog_count` is the 10T smallest cog).
- `DataType.Type.SHIFTING_FRONT_GEAR` — current front gear (1 or 2 on
  a 2x setup).
- `SavedDevices` event — carries the user-configured chainring tooth
  counts in `gearInfo.frontTeeth` (e.g. `[35, 48]`) and cassette
  tooth counts in `gearInfo.rearTeeth`. This is the channel that
  actually populates teeth on AXS pairings; the optional
  `SHIFTING_FRONT_GEAR_TEETH` field on the data stream is unreliable.

`RutagearExtension` subscribes to these streams and:

1. Identifies which front number is the small chainring by picking
   the smaller of `frontTeeth[0]` and `frontTeeth[1]` (≥ 6T gap
   required, so a 1x setup or near-identical rings won't accidentally
   trigger).
2. Reads cassette size from `SHIFTING_REAR_GEAR_MAX` when present.
3. On every rear-gear transition, fires the matching beep:
   - `gear == 1` and on the small chainring → granny.
   - `gear == cogCount` and on the big chainring → granny.
   - smallest reachable cog on the small chainring (`cogCount - 1`,
     to account for AXS chain-line protection) → small-cog.

The first emission after start is suppressed, identical-gear
emissions are deduped, and `lastGear` is held in a `@Volatile var`
on the extension service.

## Install

1. Build the debug APK locally: `./gradlew assembleDebug` — output
   ends up at `app/build/outputs/apk/debug/app-debug.apk`. The
   GitHub Actions and GitLab CI pipelines do this on every push and
   attach the APK as a downloadable artifact.
2. Sideload to a Karoo over USB:
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Open **RutaGear** from the Karoo's app drawer.
4. For each trigger:
   - Tap **Test beep** to preview the piezo pattern.
   - Tick / untick **Enabled** to gate the trigger.
5. If the small-cog beep doesn't fire when you're on the small
   chainring, open **Karoo Settings → Sensors → (your AXS System) →
   Drivetrain** and set your chainring + cassette teeth. RutaGear
   reads those values via `SavedDevices.gearInfo` to know which
   front is the small ring.

## Build pipelines

Two equivalent pipelines run on every push:

- **GitHub Actions** (`.github/workflows/build.yml`) —
  `validate-version-bump` then `build` on `ubuntu-latest` with
  Temurin JDK 17. Auths the Hammerhead Maven repo via the workflow's
  `GITHUB_TOKEN` (override with `GPR_USER` / `GPR_KEY` repo secrets
  if you have a PAT with `read:packages`).
- **GitLab CI** (`.gitlab-ci.yml`) — same validate + build stages
  plus an `install:device` step that does `adb install -r` on the
  `shell-user-bs` runner when a Karoo is USB-attached.

`validate-version-bump` refuses to build if `app/src/`, the manifest,
or `app/build.gradle.kts` changed but `versionCode` didn't increase —
catches the "I forgot to bump" footgun before it ships.

## Drivetrain config dependency

RutaGear used to ship a manual cog-count override and a "tap to mark
small chainring" button on the settings screen. Both were removed in
0.7.6 once the Karoo's drivetrain config (`SavedDevices.gearInfo`)
was confirmed to carry teeth on AXS pairings — the manual UI was
just a confusing duplicate of values that already exist in the
Karoo's own Sensors settings.

If teeth aren't set in the drivetrain config, RutaGear falls back to
the cassette size reported by `SHIFTING_REAR_GEAR_MAX` (when the
firmware populates it) and treats both chainrings as having the
same usable range. The granny beep on the small chainring at the
biggest cog will not fire in that fallback path — set the teeth and
it works.

## License

MIT — see [LICENSE](LICENSE).
