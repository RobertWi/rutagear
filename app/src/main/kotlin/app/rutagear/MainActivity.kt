// On-Karoo configuration screen for RutaGear. Programmatic LinearLayout
// (no XML) — keeps the file readable side-by-side with the extension
// service it configures and matches rutatail-karoo's MainActivity style.
//
// Persists everything to SharedPreferences("rutagear"). The extension
// service reads the same prefs each time a shift fires, so changes here
// take effect immediately without restarting the ride.

package app.rutagear

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RutaGear/UI"
        private const val PREFS = "rutagear"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var karoo: io.hammerhead.karooext.KarooSystemService
    private lateinit var beep: BeepPlayer
    private var lastToast: Toast? = null
    private var profileConsumerId: String? = null

    @Volatile private var activeProfile: io.hammerhead.karooext.models.RideProfile? = null

    private lateinit var profileLabel: TextView
    private lateinit var cogSourceLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        karoo = io.hammerhead.karooext.KarooSystemService(applicationContext)
        beep = BeepPlayer(karoo)
        karoo.connect { connected ->
            Log.i(TAG, "KarooSystem connect (UI) -> $connected, hw=${karoo.hardwareType}")
            if (connected) {
                profileConsumerId = karoo.addConsumer<io.hammerhead.karooext.models.ActiveRideProfile> { event ->
                    val p = event.profile
                    Log.i(TAG, "MainActivity ActiveRideProfile -> id=${p.id} name=${p.name}")
                    activeProfile = p
                    CogPrefs.migrateIfNeeded(prefs, p.id)
                    runOnUiThread { refreshProfileUi() }
                }
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        column.addView(TextView(this).apply {
            text = "RutaGear"
            textSize = 28f
        })
        column.addView(TextView(this).apply {
            text = "Plays a sound when your SRAM rear derailleur shifts " +
                    "to granny gear or the smallest cog."
            textSize = 13f
            setPadding(0, 8, 0, 16)
        })

        // --- Granny trigger -------------------------------------------------
        column.addView(triggerCard(
            title = "Granny (largest cog)",
            enableKey = "granny_enabled",
            playWhich = { beep.playGranny() },
            background = "#1B5E20",
        ))

        // --- Small-cog trigger ----------------------------------------------
        column.addView(triggerCard(
            title = "Small cog (highest gear)",
            enableKey = "small_enabled",
            playWhich = { beep.playSmall() },
            background = "#2E7D32",
        ))

        // --- Drivetrain status ---------------------------------------------
        // Cassette size and chainring teeth come from the Karoo's
        // drivetrain config (Sensors → AXS System → Drivetrain). When
        // teeth are set there RutaGear picks them up automatically via
        // the SavedDevices event and the SHIFTING_REAR_GEAR_MAX field.
        // No on-device override needed — the manual cog-count UI was
        // removed because it was confusing alongside auto-detect.
        column.addView(spacer(16))
        profileLabel = TextView(this).apply {
            text = "Profile: (waiting for Karoo)"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        column.addView(profileLabel)
        cogSourceLabel = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 2, 0, 4)
        }
        column.addView(cogSourceLabel)
        column.addView(TextView(this).apply {
            text = "If you're on the small chainring and the small-cog " +
                    "beep doesn't fire, set your chainring and cassette " +
                    "teeth in Karoo Settings → Sensors → (your AXS System) " +
                    "→ Drivetrain. RutaGear reads those values to know " +
                    "which front is the small ring."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 4)
        })

        // Wrap whole thing in a ScrollView — the Karoo's screen is
        // short and the cassette controls can push past one page.
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(column)
        }
        setContentView(scroll)
    }

    /** Build a single trigger configuration card (granny / small cog). */
    private fun triggerCard(
        title: String,
        enableKey: String,
        playWhich: () -> Boolean,
        background: String,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.parseColor(background))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.setMargins(0, 8, 0, 8)
            layoutParams = lp
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
        })

        val enable = CheckBox(this).apply {
            text = "Enabled"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(enableKey, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(enableKey, on).apply()
                Log.i(TAG, "$enableKey = $on")
            }
        }
        card.addView(enable)

        // Single 'Test' button — fires the piezo pattern for this
        // trigger. The earlier Pick MP3 / Clear / sound-label
        // surface was removed alongside the MP3 code path itself.
        card.addView(Button(this).apply {
            text = "Test beep"
            setOnClickListener {
                val ok = playWhich()
                val msg = if (ok) "Beep dispatched" else "Beep dispatch failed (Karoo not connected?)"
                lastToast?.cancel()
                lastToast = Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT)
                    .also { it.show() }
            }
        })
        return card
    }

    private fun refreshProfileUi() {
        val p = activeProfile
        val profileId = p?.id
        profileLabel.text = if (p == null) {
            "Profile: (no active ride profile)"
        } else {
            "Profile: ${p.name}"
        }
        val observed = CogPrefs.observed(prefs, profileId)
        val effective = CogPrefs.resolve(prefs, profileId)
        cogSourceLabel.text = buildString {
            append("$effective cogs")
            if (observed != null) append(" (from drivetrain)") else append(" (default)")
        }
    }

    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h,
        )
    }

    override fun onDestroy() {
        profileConsumerId?.let { try { karoo.removeConsumer(it) } catch (_: Throwable) {} }
        try { karoo.disconnect() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
