package tv.ezano.app

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Bundled locally: typography works on Fire TV without Google Play services or a connection.
// Variable fonts are supported by every OS version in our minSdk 26+ range.
@OptIn(ExperimentalTextApi::class)
val EzanoFont = FontFamily(
    listOf(400, 500, 600, 700, 800).map { weight ->
        Font(
            R.font.manrope,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight))
        )
    }
)

private fun tvType(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(
        fontFamily = EzanoFont,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = height.sp,
        letterSpacing = tracking.sp
    )

val EzanoTypography = Typography(
    displayLarge = tvType(42, 48, FontWeight.Bold, -.8f),
    displayMedium = tvType(36, 42, FontWeight.Bold, -.6f),
    displaySmall = tvType(32, 38, FontWeight.Bold, -.4f),
    headlineLarge = tvType(28, 34, FontWeight.Bold, -.4f),
    headlineMedium = tvType(25, 31, FontWeight.SemiBold, -.3f),
    headlineSmall = tvType(22, 28, FontWeight.SemiBold),
    titleLarge = tvType(19, 25, FontWeight.SemiBold),
    titleMedium = tvType(15, 21, FontWeight.SemiBold),
    titleSmall = tvType(13, 18, FontWeight.SemiBold),
    bodyLarge = tvType(14, 21),
    bodyMedium = tvType(12, 18),
    bodySmall = tvType(11, 16),
    labelLarge = tvType(13, 18, FontWeight.SemiBold),
    labelMedium = tvType(12, 17, FontWeight.SemiBold),
    labelSmall = tvType(10, 14, FontWeight.Medium, .2f)
)
