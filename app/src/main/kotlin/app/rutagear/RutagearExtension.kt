// RutaGear — Karoo extension that listens to SHIFTING_REAR_GEAR from
// the SRAM (or any AXS-paired) rear derailleur and plays a user-chosen
// sound on two specific transitions:
//
//   - shift TO gear 1                  → "small cog" (highest gear)
//   - shift TO gear == cog_count       → "granny"    (lowest gear)
//
// Cog index direction is the SDK's convention — Karoo emits 1-indexed
// from the smallest cog (the one at the outside, closest to the dropout).
//
// The SHIFTING_REAR_GEAR DataType carries an optional SHIFTING_REAR_GEAR_MAX
// field in its values map. When present we use it as the cassette size;
// when absent we fall back to the user-configured cog_count in prefs.
//
// This is a side-effect-only extension: no DataType, no FIT writes. The
// service exists purely so the Karoo launcher starts us with the rest
// of the extensions and we can subscribe to the gear stream via
// KarooSystemService.

package app.rutagear

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

class RutagearExtension : KarooExtension("rutagear", "0.3.0") {

    companion object {
        const val TAG = "RutaGear"
        const val PREFS = "rutagear"
    }

    override val types = emptyList<io.hammerhead.karooext.extension.DataTypeImpl>()

    private lateinit var karoo: KarooSystemService
    private var serviceJob: Job? = null
    private lateinit var beep: BeepPlayer
    private var profileConsumerId: String? = null

    @Volatile private var lastGear: Int? = null
    @Volatile private var liveMaxGear: Int? = null
    @Volatile private var activeProfile: RideProfile? = null
    @Volatile private var activeFrontGear: Int? = null
    // Shift direction: -1 down, +1 up, 0 initial. Used to detect
    // down→up reversals; the previous gear at that moment is a
    // confirmed local minimum (the actual valley floor the rider
    // reached, not a transit gear during a fast ascent).
    @Volatile private var lastShiftDirection: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Extension onCreate — pkg=${applicationContext.packageName}")
        karoo = KarooSystemService(applicationContext)
        beep = BeepPlayer(karoo)
        karoo.connect { connected ->
            if (!connected) {
                Log.w(TAG, "KarooSystem connect callback: not connected")
                return@connect
            }
            Log.i(TAG, "KarooSystem connected — subscribing to gear stream + profile")
            profileConsumerId = karoo.addConsumer<ActiveRideProfile> { event ->
                val p = event.profile
                Log.i(TAG, "ActiveRideProfile -> id=${p.id} name=${p.name}")
                activeProfile = p
                val prefs = applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE)
                CogPrefs.migrateIfNeeded(prefs, p.id)
                CogPrefs.observed(prefs, p.id)?.let { liveMaxGear = it }
            }
            // SavedDevices event (karoo-ext 1.1.5+) carries the
            // user-configured chainring tooth counts via
            // SavedDevice.gearInfo.frontTeeth. THIS is the channel
            // that actually has teeth on AXS pairings — the optional
            // SHIFTING_FRONT_GEAR_TEETH field on the data stream
            // is not populated even though the Karoo system has the
            // values, but it does deliver them via SavedDevices.
            karoo.addConsumer { event: SavedDevices ->
                for (dev in event.devices) {
                    val gi = dev.gearInfo ?: continue
                    val ft = gi.frontTeeth ?: continue
                    if (ft.size < 2) continue
                    Log.i(TAG, "SavedDevice ${dev.name} gearInfo frontTeeth=$ft rearTeeth=${gi.rearTeeth}")
                    // frontTeeth is ordered by front-gear-index — front=1 → ft[0],
                    // front=2 → ft[1]. Whichever is smaller is the small ring.
                    val smallFront = if (ft[0] < ft[1]) 1 else 2
                    val prefs = applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE)
                    val profileId = activeProfile?.id ?: "_default"
                    if (CogPrefs.smallRingFront(prefs, profileId) != smallFront) {
                        CogPrefs.setSmallRingFront(prefs, profileId, smallFront)
                        Log.i(TAG, "auto-detected small chainring via SavedDevices: front=$smallFront " +
                                "(front1=${ft[0]}T, front2=${ft[1]}T)")
                    }
                }
            }
            serviceJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                launch { observeGear() }
                launch { observeFrontGear() }
                launch { observeShiftingGears() }
            }
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        profileConsumerId?.let { try { karoo.removeConsumer(it) } catch (_: Throwable) {} }
        try { karoo.disconnect() } catch (_: Throwable) {}
        super.onDestroy()
    }

    /**
     * Side-channel: watch the front chainring so the small-cog trigger
     * can use a (profile, front-gear)-keyed minimum reachable rear gear.
     * AXS chain-line protection blocks the rear from gear 1 when on the
     * small ring, so the trigger needs to fire at whatever the actual
     * reachable minimum is for the current front, not a hard-coded 1.
     */
    // Per-front tooth count observed this process lifetime (if the
    // derailleur publishes Field.SHIFTING_FRONT_GEAR_TEETH on either
    // SHIFTING_FRONT_GEAR or SHIFTING_GEARS).
    private val frontTeethByFront = mutableMapOf<Int, Int>()

    private suspend fun observeFrontGear() {
        karoo.streamDataFlow(DataType.Type.SHIFTING_FRONT_GEAR).collect { state ->
            if (state !is StreamState.Streaming) return@collect
            val values = state.dataPoint.values
            val front = state.dataPoint.singleValue?.toInt() ?: return@collect
            if (front != activeFrontGear) {
                Log.i(TAG, "front gear -> $front")
                activeFrontGear = front
                // Stash the live front-gear number per profile so the
                // settings UI can read it when the rider taps "mark
                // current chainring as small". prefs are the cheapest
                // IPC channel between extension service and activity.
                val prefs = applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE)
                val pid = activeProfile?.id ?: "_default"
                prefs.edit().putInt("last_front_$pid", front).apply()
            }
            val teeth = values[DataType.Field.SHIFTING_FRONT_GEAR_TEETH]?.toInt()
            if (teeth != null && teeth > 0) {
                identifySmallRingByTeeth(front, teeth)
            }
        }
    }

    /**
     * Combined gears stream — same diagnostic logging, since the SDK
     * docs claim FRONT_GEAR_TEETH lives in its Fields list too.
     */
    private suspend fun observeShiftingGears() {
        karoo.streamDataFlow("TYPE_SHIFTING_GEARS_ID").collect { state ->
            if (state !is StreamState.Streaming) return@collect
            val values = state.dataPoint.values
            val front = values[DataType.Field.SHIFTING_FRONT_GEAR]?.toInt() ?: return@collect
            val teeth = values[DataType.Field.SHIFTING_FRONT_GEAR_TEETH]?.toInt()
            if (teeth != null && teeth > 0) {
                identifySmallRingByTeeth(front, teeth)
            }
        }
    }

    /**
     * Persist the small-ring assignment as soon as we have tooth
     * counts for both fronts. When the derailleur publishes the
     * teeth field this is unambiguous.
     */
    private fun identifySmallRingByTeeth(front: Int, teeth: Int) {
        val prev = frontTeethByFront[front]
        if (prev == teeth) return
        frontTeethByFront[front] = teeth
        Log.i(TAG, "front $front teeth -> $teeth")
        if (frontTeethByFront.size < 2) return
        val prefs = applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE)
        val profileId = activeProfile?.id ?: "_default"
        val (a, b) = frontTeethByFront.entries.sortedBy { it.value }.let { it[0] to it[1] }
        if (b.value - a.value < 6) return
        val smallFront = a.key
        if (CogPrefs.smallRingFront(prefs, profileId) != smallFront) {
            CogPrefs.setSmallRingFront(prefs, profileId, smallFront)
            Log.i(TAG, "auto-detected small chainring via teeth: front=$smallFront " +
                    "($smallFront=${a.value}T, ${b.key}=${b.value}T)")
        }
    }

    private suspend fun observeGear() {
        karoo.streamDataFlow(DataType.Type.SHIFTING_REAR_GEAR).collect { state ->
            if (state !is StreamState.Streaming) return@collect

            val values = state.dataPoint.values
            val gear = state.dataPoint.singleValue?.toInt() ?: return@collect

            val prefs = applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE)
            // Fall back to a sentinel '_default' profile id when the
            // rider hasn't picked a Karoo ride profile — keeps the
            // per-profile auto-learn (observed cassette size,
            // per-front-gear floor) usable without forcing the
            // rider through profile setup. If they later DO pick a
            // profile, that gets its own slot and starts fresh; the
            // _default data stays as a global fallback.
            val profileId = activeProfile?.id ?: "_default"

            // SHIFTING_REAR_GEAR_MAX is an optional field in the same
            // DataPoint. When the derailleur reports it we prefer it
            // over the prefs fallback, and persist it keyed by the
            // active profile so the same cassette is remembered next
            // ride on this bike — and a different bike (different
            // profile) keeps its own observed value.
            values[DataType.Field.SHIFTING_REAR_GEAR_MAX]?.toInt()?.let { max ->
                if (max > 0 && max != liveMaxGear) {
                    Log.i(TAG, "REAR_GEAR_MAX -> $max (from field) profile=$profileId")
                    liveMaxGear = max
                    CogPrefs.writeObserved(prefs, profileId, max)
                }
            }

            val prev = lastGear
            if (prev == gear) return@collect

            // Direction of this shift, then look for a down→up
            // reversal. That's the ONLY moment we can confirm prev
            // was an actual valley floor (rider went down to it then
            // chose to climb back out). 0.6.0/0.6.1's
            // "mark prev on every up-shift" semantics treated every
            // transit gear during a fast ascent as a bottom, which
            // then beep-stormed on every subsequent descent that
            // passed through one. Pure direction-reversal fixes it.
            val newDir = when {
                prev == null -> 0
                gear > prev  -> 1
                else         -> -1
            }
            val reversedDownToUp = lastShiftDirection == -1 && newDir == 1
            if (reversedDownToUp && prev != null) {
                // prev IS the floor of the just-finished descent.
                // Persist as per-front minimum (the actual lowest
                // reachable cog for this chainring config, confirmed
                // by the rider's behavior). markBottomGear mirrors
                // into the global set for diagnostics. No other
                // writes to minLearned happen mid-emission so the
                // trigger's smallCogThreshold stays stable except on
                // reversals — no beep storm on multi-cog descents.
                CogPrefs.markBottomGear(prefs, profileId, prev)
                activeFrontGear?.let { f ->
                    CogPrefs.bumpLearnedMin(prefs, profileId, f, prev)
                }
            }
            if (newDir != 0) lastShiftDirection = newDir
            lastGear = gear

            val cogCount = liveMaxGear ?: CogPrefs.resolve(prefs, profileId)
            val smallCogThreshold = CogPrefs.minLearned(prefs, profileId, activeFrontGear)
            val grannyEnabled = prefs.getBoolean("granny_enabled", true)
            val smallEnabled  = prefs.getBoolean("small_enabled", true)

            // Max-cog (high-water mark) is monotonic so still safe to
            // bump per-emission.
            CogPrefs.bumpLearned(prefs, profileId, gear)

            Log.i(TAG, "gear ${prev ?: "-"} -> $gear dir=$newDir lastDir=$lastShiftDirection (max=$cogCount minSmall=$smallCogThreshold front=$activeFrontGear granny=$grannyEnabled small=$smallEnabled)")

            // First emission after start gives prev=null. Don't fire
            // on the initial sample — we'd play whichever cog the
            // rider happens to be in when the app starts.
            if (prev == null) return@collect

            // SRAM AXS chain-line protection blocks the rear from
            // reaching the smallest cog (gear==cogCount) when on the
            // small chainring, so a hard `gear==cogCount` granny
            // trigger can never fire there. The rider's natural
            // "end of useful range" on the small ring is the BIGGEST
            // cog (gear==1, the climbing extreme); on the big ring
            // it stays gear==cogCount (the speed extreme). This is
            // why granny suddenly went silent on small-ring rides
            // after teeth config landed — the cassette physically
            // can't reach 10T from 35T.
            val isSmallRing = activeFrontGear != null &&
                CogPrefs.smallRingFront(prefs, profileId) == activeFrontGear
            val grannyGear = if (isSmallRing) 1 else cogCount

            when {
                smallEnabled && gear == smallCogThreshold -> {
                    Log.i(TAG, "SMALL cog trigger (threshold=$smallCogThreshold)")
                    beep.playSmall()
                }
                gear == grannyGear && grannyEnabled -> {
                    Log.i(TAG, "GRANNY cog trigger (grannyGear=$grannyGear smallRing=$isSmallRing)")
                    beep.playGranny()
                }
                else -> { /* intermediate gears — ignore */ }
            }
        }
    }
}

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySendBlocking(event.state)
    }
    awaitClose { removeConsumer(listenerId) }
}
