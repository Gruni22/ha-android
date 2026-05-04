package io.homeassistant.btdashboard.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.homeassistant.btdashboard.R

private val ICON_SIZE = 120.dp
private val MAX_CONTENT_WIDTH = 480.dp

@Composable
fun WelcomeScreen(
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(0.2f))

        Image(
            imageVector = ImageVector.vectorResource(R.drawable.ic_home_assistant_branding),
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
        )

        WelcomeText()

        Spacer(Modifier.weight(0.8f))

        BottomButtons(onConnectClick = onConnectClick)
    }
}

@Composable
private fun ColumnScope.WelcomeText() {
    // Both styles use textAlign = Center, matching HATextStyle.Headline / HATextStyle.Body
    Text(
        text = "Home Assistant\nBluetooth",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.W500,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.bt_welcome_details),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.BottomButtons(onConnectClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Pill-shaped accent button — matches companion's HAAccentButton
        Button(
            onClick = onConnectClick,
            modifier = Modifier
                .widthIn(max = MAX_CONTENT_WIDTH)
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                stringResource(R.string.bt_welcome_connect),
                fontWeight = FontWeight.W500,
            )
        }
    }
}
