// Karoo-native sound output via PlayBeepPattern. This is the only path
// that reliably produces audible sound on Karoo 2 (piezo buzzer) AND
// Karoo 3 (internal speaker). Standard MediaPlayer is silent on both
// devices unless a BT audio sink is paired — Karoo's audio output is
// gated to system effects fired through karoo-ext.
//
// Patterns are short, distinct, and different in direction so the rider
// can tell granny vs small-cog without looking down.

package app.rutagear

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.PlayBeepPattern

class BeepPlayer(private val karoo: KarooSystemService) {

    companion object {
        private const val TAG = "RutaGear/Beep"

        // Frequencies tuned to piezo resonance (~2700 Hz) for K2,
        // still inside K3 speaker's flat response. Patterns shortened
        // from the 5-tone arpeggio to a tighter 2-3 tone sequence
        // that doesn't drag past the shift moment — riders flagged
        // the previous 1.2-second beep felt sluggish.
        //
        // GRANNY: short low growl — single descending step.
        // "boop-doop" — calm but unmistakable, fires when chain
        // lands on the biggest cog.
        val GRANNY: List<PlayBeepPattern.Tone> = listOf(
            PlayBeepPattern.Tone(2700, 90),
            PlayBeepPattern.Tone(null,  40),
            PlayBeepPattern.Tone(1800, 240),
        )

        // SMALL_COG: triple peppy chirp at full resonance.
        // "pip-pip-piiiip" — high, snappy, energetic. Fires when
        // chain lands on the smallest reachable cog.
        val SMALL_COG: List<PlayBeepPattern.Tone> = listOf(
            PlayBeepPattern.Tone(2700, 70),
            PlayBeepPattern.Tone(null,  50),
            PlayBeepPattern.Tone(2700, 70),
            PlayBeepPattern.Tone(null,  50),
            PlayBeepPattern.Tone(3000, 180),
        )
    }

    fun playGranny(): Boolean = play("granny", GRANNY)
    fun playSmall(): Boolean  = play("small_cog", SMALL_COG)

    private fun play(label: String, tones: List<PlayBeepPattern.Tone>): Boolean {
        val ok = try {
            karoo.dispatch(PlayBeepPattern(tones))
        } catch (e: Throwable) {
            Log.e(TAG, "dispatch failed for $label", e)
            false
        }
        Log.i(TAG, "$label dispatch -> $ok (${tones.size} tones)")
        return ok
    }
}
