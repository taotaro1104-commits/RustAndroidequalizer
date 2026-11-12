package dev.rusteq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.rusteq.PlayerController
import dev.rusteq.R
import dev.rusteq.audio.EqConfig
import kotlin.math.roundToInt

/** Reverb, exciter, dynamic EQ and the true-peak limiter, shown on the Space page. */
@Composable
internal fun ReverbPanel(c: EqConfig, vm: PlayerController) {
    var remembered by rememberSaveable { mutableFloatStateOf(.25f) }
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_fdn_heading), stringResource(R.string.ui_fdn), c.reverbMix > 0f, AudioColors.Blue) { on ->
            if (!on) remembered = c.reverbMix
            vm.configure { it.copy(reverbMix = if (on) remembered.coerceAtLeast(.05f) else 0f) }
        }
        Text(stringResource(R.string.ui_fdn_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.reverbMix > 0f) {
            SettingSlider(stringResource(R.string.ui_fdn_mix), "${(c.reverbMix * 100).roundToInt()}%", c.reverbMix, .01f..1f) { v -> vm.configure { it.copy(reverbMix = v) } }
            SettingSlider(stringResource(R.string.ui_fdn_decay), "%.1f s".format(c.reverbDecayS), c.reverbDecayS, EqConfig.MIN_REVERB_S..EqConfig.MAX_REVERB_S) { v -> vm.configure { it.copy(reverbDecayS = v) } }
            SettingSlider(stringResource(R.string.ui_fdn_damping), "${(c.reverbDamping * 100).roundToInt()}%", c.reverbDamping, 0f..1f) { v -> vm.configure { it.copy(reverbDamping = v) } }
        }
    }
}

@Composable
internal fun ExciterPanel(c: EqConfig, vm: PlayerController) {
    var remembered by rememberSaveable { mutableFloatStateOf(.3f) }
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_exciter_heading), stringResource(R.string.ui_exciter), c.exciterAmount > 0f, AudioColors.Blue) { on ->
            if (!on) remembered = c.exciterAmount
            vm.configure { it.copy(exciterAmount = if (on) remembered.coerceAtLeast(.05f) else 0f) }
        }
        Text(stringResource(R.string.ui_exciter_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.exciterAmount > 0f) {
            SettingSlider(stringResource(R.string.ui_exciter_amount), "${(c.exciterAmount * 100).roundToInt()}%", c.exciterAmount, .01f..1f) { v -> vm.configure { it.copy(exciterAmount = v) } }
            SettingSlider(stringResource(R.string.ui_exciter_hz), "%.1f kHz".format(c.exciterHz / 1000f), c.exciterHz, 1500f..9000f) { v -> vm.configure { it.copy(exciterHz = v) } }
        }
    }
}

@Composable
internal fun DynamicEqPanel(c: EqConfig, vm: PlayerController) {
    var remembered by rememberSaveable { mutableFloatStateOf(6f) }
    AudioPanel(tint = AudioColors.BassTint) {
        ToggleHeading(stringResource(R.string.ui_dyneq_heading), stringResource(R.string.ui_dyneq), c.dynEqRangeDb > 0f, AudioColors.Bass) { on ->
            if (!on) remembered = c.dynEqRangeDb
            vm.configure { it.copy(dynEqRangeDb = if (on) remembered.coerceAtLeast(1f) else 0f) }
        }
        Text(stringResource(R.string.ui_dyneq_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.dynEqRangeDb > 0f) {
            SettingSlider(stringResource(R.string.ui_dyneq_range), "%.0f dB".format(c.dynEqRangeDb), c.dynEqRangeDb, 1f..18f, AudioColors.Bass) { v -> vm.configure { it.copy(dynEqRangeDb = v) } }
            SettingSlider(stringResource(R.string.ui_dyneq_hz), "${c.dynEqHz.roundToInt()} Hz", c.dynEqHz, 40f..600f, AudioColors.Bass) { v -> vm.configure { it.copy(dynEqHz = v) } }
            SettingSlider(stringResource(R.string.ui_dyneq_threshold), "${c.dynEqThresholdDb.roundToInt()} dB", c.dynEqThresholdDb, -60f..0f, AudioColors.Bass) { v -> vm.configure { it.copy(dynEqThresholdDb = v) } }
            SettingSlider(stringResource(R.string.ui_dyneq_ratio), "%.1f : 1".format(c.dynEqRatio), c.dynEqRatio, 1f..10f, AudioColors.Bass) { v -> vm.configure { it.copy(dynEqRatio = v) } }
        }
    }
}

@Composable
internal fun LimiterPanel(c: EqConfig, vm: PlayerController) {
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_tp_heading), stringResource(R.string.ui_tp), c.truePeakLimiter, AudioColors.Blue) { on ->
            vm.configure { it.copy(truePeakLimiter = on) }
        }
        Text(stringResource(R.string.ui_tp_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
    }
}
