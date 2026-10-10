package com.lifeline.mesh.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.LifelineTheme
import com.lifeline.mesh.ui.theme.Space

/** What Health Connect shows when someone asks why this app reads health data (required by Health Connect). */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LifelineTheme {
                Column(
                    Modifier.fillMaxSize().background(Lifeline.colors.bg).safeDrawingPadding().padding(Space.screen),
                    verticalArrangement = Arrangement.spacedBy(Space.l),
                ) {
                    Text("Why Lifeline Mesh reads your watch data", style = Lifeline.type.title, color = Lifeline.colors.ink)
                    Text(
                        "Only if you switch on Settings → Safety → Watch vitals alarm. The app reads blood oxygen, blood " +
                            "pressure and heart rate that your watch saved in Health Connect, on this phone only. If a reading " +
                            "is far outside normal, the phone rings for 15 seconds; if nobody taps I'm OK, it sends an urgent " +
                            "ambulance request with your location and that one reading to nearby phones and rescue teams. " +
                            "Nothing else is read, stored or sent, and nothing is written to Health Connect.",
                        style = Lifeline.type.body, color = Lifeline.colors.ink,
                    )
                    PrimaryButton("OK", { finish() })
                }
            }
        }
    }
}
