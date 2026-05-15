// Per-bike-profile cog count storage. Cassettes vary by bike (road
// 11-28, gravel 11-44, MTB 10-52) and even between race/training on
// the same frame. Karoo's ActiveRideProfile event gives us a stable
// String id per profile, so we key all cog-count prefs by that.
//
// Pref layout (all keys live in SharedPreferences "rutagear"):
//   cog_count                  legacy global manual value (default 12)
//   cog_count:<profileId>      manual cassette size for this profile
//   cog_count_observed:<id>    cassette size reported by the derailleur
//                              via SHIFTING_REAR_GEAR_MAX field (when
//                              the firmware actually populates it —
//                              real SRAM AXS pairings observed in
//                              practice often leave it empty)
//   cog_count_observed_at:<id> epoch-ms when MAX was last seen
//   cog_count_learned:<id>     highest gear index ever emitted on
//                              SHIFTING_REAR_GEAR for this profile.
//                              Used as a fallback when the SDK field
//                              never populates — auto-learns the
//                              cassette size from observed ride data.
//   cog_count_learned_at:<id>  epoch-ms when learned value last grew
//
// Resolution order at trigger time:
//   1. manual for this profile    (explicit user override — wins over
//      auto-detect because the rider knows their own bike, and firmware
//      values have occasionally been wrong in practice)
//   2. observed for this profile  (from the derailleur firmware via
//      SHIFTING_REAR_GEAR_MAX field, when it actually shows up)
//   3. learned for this profile   (highest gear ever seen on this bike)
//   4. legacy global cog_count    (only until first profile is seen)
//   5. 12                         (12-speed default for modern SRAM)
//
// "Reset to auto" in the UI removes cog_count:<profileId> so resolution
// falls back through observed → learned → 12.

package app.rutagear

import android.content.SharedPreferences
import android.util.Log

object CogPrefs {

    private const val TAG = "RutaGear/CogPrefs"
    private const val LEGACY_KEY = "cog_count"
    private const val DEFAULT = 12

    fun keyManual(profileId: String) = "cog_count:$profileId"
    fun keyObserved(profileId: String) = "cog_count_observed:$profileId"
    fun keyObservedAt(profileId: String) = "cog_count_observed_at:$profileId"
    fun keyLearned(profileId: String) = "cog_count_learned:$profileId"
    fun keyLearnedAt(profileId: String) = "cog_count_learned_at:$profileId"

    // Per-(profile, front-gear) reachable-minimum rear gear. Solves
    // SRAM AXS chain-line protection: when the chain is on the small
    // front ring the system blocks the rear from reaching gear 1, so
    // a naive "gear == 1" small-cog trigger never fires. Tracked as
    // a high-water mark INVERTED — the lowest gear ever seen for
    // each (profile, front) combination.
    fun keyMinLearned(profileId: String, frontGear: Int) =
        "cog_min_learned:$profileId:front$frontGear"

    // Heuristic learner that doesn't depend on SHIFTING_FRONT_GEAR
    // being broadcast. A gear is recorded as a "bottom" the moment
    // the rider shifts UP out of it — that's the only time we know
    // for sure the previous gear was the lowest they could (or
    // chose to) reach. Stored as a comma-separated CSV of integers
    // per profile, capped to 6 entries so a noisy gear stream
    // can't fill prefs.
    fun keyBottomGears(profileId: String) = "cog_bottom_gears:$profileId"
    private const val BOTTOM_GEARS_CAP = 6

    /**
     * Resolve the cog count to use for TRIGGERING during a ride.
     * Falls back: manual -> observed -> legacy global -> 12.
     *
     * Deliberately does NOT consider the auto-learned value.
     * Earlier revision included it and produced the
     * 'beeps on every sprocket past the middle' bug Joachim
     * reported: bumpLearned() promotes learned to each new highest
     * gear in the same tick, then resolve() returns that bumped
     * value, then `gear == cogCount` fires on every fresh max
     * shift. Learned is still tracked (see resolveForUi) so the
     * settings screen can suggest a manual value, but the trigger
     * gate needs a stable cogCount that doesn't move mid-ride.
     */
    fun resolve(prefs: SharedPreferences, profileId: String?): Int {
        if (profileId != null) {
            val man = prefs.getInt(keyManual(profileId), -1)
            if (man > 0) return man
            val obs = prefs.getInt(keyObserved(profileId), -1)
            if (obs > 0) return obs
        }
        return prefs.getInt(LEGACY_KEY, DEFAULT)
    }

    /**
     * Resolve for UI display — same as resolve() but includes the
     * learned tier as a hint when nothing more authoritative exists.
     * Useful for the "we've seen up to gear N on this bike" copy in
     * settings.
     */
    fun resolveForUi(prefs: SharedPreferences, profileId: String?): Int {
        if (profileId != null) {
            val man = prefs.getInt(keyManual(profileId), -1)
            if (man > 0) return man
            val obs = prefs.getInt(keyObserved(profileId), -1)
            if (obs > 0) return obs
            val learned = prefs.getInt(keyLearned(profileId), -1)
            if (learned > 0) return learned
        }
        return prefs.getInt(LEGACY_KEY, DEFAULT)
    }

    /** True if the user has set an explicit manual override for this profile. */
    fun hasManual(prefs: SharedPreferences, profileId: String?): Boolean {
        if (profileId == null) return false
        return prefs.getInt(keyManual(profileId), -1) > 0
    }

    /** Clear the manual override so resolution falls back to observed/learned. */
    fun clearManual(prefs: SharedPreferences, profileId: String?) {
        if (profileId == null) return
        prefs.edit().remove(keyManual(profileId)).apply()
        Log.i(TAG, "cleared manual override for $profileId")
    }

    // Per-profile auto-detected small-chainring front number. 0 =
    // not yet identified. Set by observeGearRatio() once it has seen
    // both fronts at the same rear cog and can compare ratios.
    fun keySmallRingFront(profileId: String) = "small_ring_front:$profileId"

    fun smallRingFront(prefs: SharedPreferences, profileId: String?): Int {
        if (profileId == null) return 0
        return prefs.getInt(keySmallRingFront(profileId), 0)
    }

    fun setSmallRingFront(prefs: SharedPreferences, profileId: String?, front: Int) {
        if (profileId == null) return
        prefs.edit().putInt(keySmallRingFront(profileId), front).apply()
    }

    /**
     * Lowest reachable rear gear ever observed for this (profile,
     * front-gear) combination. Falls back to 1 (the absolute smallest
     * cog) before any data is collected.
     *
     * When auto-detect has identified frontGear as the SMALL chainring
     * (via SHIFTING_GEAR_RATIO comparison in
     * RutagearExtension.observeGearRatio), the floor is pinned to
     * cogCount-1 — that's the practical AXS chain-line-protected
     * minimum (11T on a 12-speed). This bypasses the direction-
     * reversal learner whose floor would otherwise be polluted if the
     * rider ever physically reached gear 1 on the small ring (e.g.
     * during testing on a stand).
     *
     * On the BIG chainring or when auto-detect hasn't run yet, fall
     * back to the direction-reversal-learned floor.
     */
    fun minLearned(prefs: SharedPreferences, profileId: String?, frontGear: Int?): Int {
        if (profileId == null || frontGear == null) return 1
        if (smallRingFront(prefs, profileId) == frontGear) {
            val cogCount = prefs.getInt(keyObserved(profileId), -1).takeIf { it > 0 }
                ?: prefs.getInt(keyManual(profileId), -1).takeIf { it > 0 }
                ?: prefs.getInt(LEGACY_KEY, DEFAULT)
            return (cogCount - 1).coerceAtLeast(1)
        }
        val v = prefs.getInt(keyMinLearned(profileId, frontGear), -1)
        return if (v > 0) v else 1
    }

    /**
     * Wipe per-front learned floors and the bottom-gears set for the
     * given profile. Useful when the rider has tested by reaching
     * gears that AXS chain-line protection would normally block —
     * the polluted learned data needs to be cleared so subsequent
     * real-world rides can teach the correct practical floor.
     */
    fun resetLearnedFloors(prefs: SharedPreferences, profileId: String?) {
        if (profileId == null) return
        val editor = prefs.edit()
        for (f in 1..3) editor.remove(keyMinLearned(profileId, f))
        editor.remove(keyBottomGears(profileId))
        editor.remove(keySmallRingFront(profileId))
        editor.apply()
        Log.i(TAG, "reset learned floors + bottom-gears + small-ring for $profileId")
    }

    /**
     * Drop the learned minimum lower if [gear] is below the current
     * stored minimum. Returns true if a new low was recorded.
     */
    fun bumpLearnedMin(
        prefs: SharedPreferences,
        profileId: String,
        frontGear: Int,
        gear: Int,
    ): Boolean {
        if (gear <= 0) return false
        val prev = prefs.getInt(keyMinLearned(profileId, frontGear), Int.MAX_VALUE)
        if (gear >= prev) return false
        prefs.edit()
            .putInt(keyMinLearned(profileId, frontGear), gear)
            .apply()
        Log.i(TAG, "learned min[$profileId,front$frontGear] $prev -> $gear")
        return true
    }

    /**
     * Front-gear-independent "bottom" set: which rear gears have
     * been observed as a local minimum (rider shifted UP out of
     * them). Lets the small-cog trigger fire even when SHIFTING_
     * FRONT_GEAR isn't broadcast — the small chainring shows up
     * organically because the rider can't shift below 11T there,
     * so 11T becomes a recurring local minimum.
     */
    fun bottomGears(prefs: SharedPreferences, profileId: String?): Set<Int> {
        if (profileId == null) return emptySet()
        val csv = prefs.getString(keyBottomGears(profileId), "") ?: ""
        if (csv.isEmpty()) return emptySet()
        return csv.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    /**
     * Record [gear] as a local-minimum sighting. Capped to the
     * BOTTOM_GEARS_CAP most recent unique values. Returns true if
     * the gear was newly added.
     */
    fun markBottomGear(prefs: SharedPreferences, profileId: String?, gear: Int): Boolean {
        if (profileId == null || gear <= 0) return false
        val existing = bottomGears(prefs, profileId)
        if (gear in existing) return false
        // Append + cap from the left (oldest evicted first).
        val updated = (existing + gear).toList().takeLast(BOTTOM_GEARS_CAP)
        prefs.edit()
            .putString(keyBottomGears(profileId), updated.joinToString(","))
            .apply()
        Log.i(TAG, "bottom-gears[$profileId] += $gear -> $updated")
        return true
    }

    /**
     * Resolve specifically the manual fallback (what the EditText
     * should display). Doesn't consider the observed value.
     */
    fun resolveManual(prefs: SharedPreferences, profileId: String?): Int {
        if (profileId != null) {
            val man = prefs.getInt(keyManual(profileId), -1)
            if (man > 0) return man
        }
        return prefs.getInt(LEGACY_KEY, DEFAULT)
    }

    /** Read the observed value for this profile, or null if none seen yet. */
    fun observed(prefs: SharedPreferences, profileId: String?): Int? {
        if (profileId == null) return null
        val v = prefs.getInt(keyObserved(profileId), -1)
        return if (v > 0) v else null
    }

    /**
     * Read the learned value (highest gear ever emitted on this
     * profile), or null if nothing learned yet.
     */
    fun learned(prefs: SharedPreferences, profileId: String?): Int? {
        if (profileId == null) return null
        val v = prefs.getInt(keyLearned(profileId), -1)
        return if (v > 0) v else null
    }

    /** Persist an observed MAX coming off the derailleur's field. */
    fun writeObserved(prefs: SharedPreferences, profileId: String, max: Int) {
        prefs.edit()
            .putInt(keyObserved(profileId), max)
            .putLong(keyObservedAt(profileId), System.currentTimeMillis())
            .apply()
        Log.i(TAG, "observed[$profileId] = $max")
    }

    /**
     * Bump the learned value if [gear] exceeds the current high-water
     * mark. Returns true if a new high was recorded.
     */
    fun bumpLearned(prefs: SharedPreferences, profileId: String, gear: Int): Boolean {
        if (gear <= 0) return false
        val prev = prefs.getInt(keyLearned(profileId), -1)
        if (gear <= prev) return false
        prefs.edit()
            .putInt(keyLearned(profileId), gear)
            .putLong(keyLearnedAt(profileId), System.currentTimeMillis())
            .apply()
        Log.i(TAG, "learned[$profileId] $prev -> $gear (new high gear)")
        return true
    }

    /** Persist the manual EditText value for this profile. */
    fun writeManual(prefs: SharedPreferences, profileId: String?, value: Int) {
        if (profileId == null) {
            prefs.edit().putInt(LEGACY_KEY, value).apply()
            Log.i(TAG, "manual[<no profile>] = $value (legacy global)")
            return
        }
        prefs.edit().putInt(keyManual(profileId), value).apply()
        Log.i(TAG, "manual[$profileId] = $value")
    }

    /**
     * First-sight migration — if a profile has no per-profile manual
     * value yet and the legacy global has a non-default value, copy it
     * so existing users don't lose their setting on upgrade.
     */
    fun migrateIfNeeded(prefs: SharedPreferences, profileId: String) {
        if (prefs.contains(keyManual(profileId))) return
        val legacy = prefs.getInt(LEGACY_KEY, -1)
        if (legacy <= 0) return
        prefs.edit().putInt(keyManual(profileId), legacy).apply()
        Log.i(TAG, "migrated legacy cog_count=$legacy -> manual[$profileId]")
    }
}
