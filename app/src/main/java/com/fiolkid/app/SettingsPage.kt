package com.fiolkid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fiolkid.app.engine.UpdateState

/** Обновления для экрана: состояние и что делать по кнопкам. */
class UpdateUi(val state: UpdateState, val onCheck: () -> Unit, val onInstall: () -> Unit)

/**
 * «Настройки» в стиле версии для компьютера: заголовок и панели. Пока на телефоне — только обновления;
 * ключ, подписка, способ обхода и Telegram появятся следующими.
 */
@Composable
fun SettingsPage(version: String, update: UpdateUi?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 22.u, end = 22.u, top = 56.u, bottom = 20.u),
    ) {
        BasicText(
            "ТВОЙ FIOLKID",
            style = TextStyle(color = Color(0xFFD1AA72), fontSize = 10.5.us, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.us),
        )
        BasicText(
            "Настройки", Modifier.padding(top = 4.u),
            style = TextStyle(color = WinterColors.Text, fontSize = 30.us, fontWeight = FontWeight.SemiBold),
        )
        BasicText(
            "Обновления и будущие разделы", Modifier.padding(top = 6.u, bottom = 18.u),
            style = TextStyle(color = Color(0xFFC0C0CA), fontSize = 12.5.us),
        )

        Panel {
            val available = update?.state?.available
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PanelTitle("Обновления")
                    BasicText(
                        "FiolKid $version", Modifier.padding(top = 3.u),
                        style = TextStyle(color = Color(0xFFC0C0CA), fontSize = 12.us),
                    )
                }
                Pill(if (available == null) "ТЕКУЩАЯ" else "ЕСТЬ НОВАЯ", highlighted = available != null)
            }
            val status = update?.state?.status.orEmpty()
            if (status.isNotEmpty())
                BasicText(status, Modifier.padding(top = 12.u), style = TextStyle(color = Color(0xFFDCD8D2), fontSize = 12.5.us))
            if (update != null) {
                if (available != null)
                    GoldButton("Обновить до ${available.version}", enabled = !update.state.busy, onClick = update.onInstall)
                SecondaryButton("Проверить обновления", enabled = !update.state.busy, onClick = update.onCheck)
            }
        }

        Panel {
            PanelTitle("Скоро")
            BasicText(
                "Ключ Amnezia и подписка для нейросетей, выбор способа обхода, Telegram, " +
                    "новогоднее оформление — как в версии для компьютера.",
                Modifier.padding(top = 8.u),
                style = TextStyle(color = Color(0xFFC0C0CA), fontSize = 12.5.us),
            )
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.u)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.u)
            .background(Brush.verticalGradient(listOf(Color(0xF0292B36), Color(0xF01D1F28))), shape)
            .border(1.dp, Color(0x26BFB5A5), shape)
            .padding(19.u),
    ) { content() }
}

@Composable
private fun PanelTitle(text: String) =
    BasicText(text, style = TextStyle(color = WinterColors.Text, fontSize = 16.us, fontWeight = FontWeight.SemiBold))

@Composable
private fun Pill(text: String, highlighted: Boolean) {
    Box(
        Modifier.background(if (highlighted) Color(0x33D1AA72) else Color(0x14FFFFFF), RoundedCornerShape(9.u))
            .padding(horizontal = 9.u, vertical = 4.u),
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = if (highlighted) Color(0xFFEBD2AD) else Color(0xFFB6B9C5),
                fontSize = 9.5.us, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.us,
            ),
        )
    }
}

@Composable
private fun GoldButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.u)
    Box(
        Modifier.padding(top = 14.u).fillMaxWidth().heightIn(min = 40.u).alpha(if (enabled) 1f else 0.55f)
            .background(Brush.verticalGradient(listOf(Color(0xFFBDA079), Color(0xFF927753))), shape)
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = Color(0xFF191B22), fontSize = 13.us, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.u)
    Box(
        Modifier.padding(top = 10.u).fillMaxWidth().heightIn(min = 40.u).alpha(if (enabled) 1f else 0.55f)
            .background(Color(0x1FFFFFFF), shape)
            .border(1.dp, Color(0x14FFFFFF), shape)
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = Color(0xFFE6DFD4), fontSize = 13.us, fontWeight = FontWeight.Medium))
    }
}
