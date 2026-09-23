package com.lotanbar.tripexplorer.ui.theme

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection

/**
 * True if the string contains any RTL character (Hebrew / Arabic). "Any RTL wins" rather than
 * "first strong character", because the soft keyboard often prepends an invisible LTR mark to
 * Hebrew input, which would fool a first-strong heuristic.
 */
fun String.isRtl(): Boolean = any { char ->
    val d = Character.getDirectionality(char)
    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE ||
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE
}

fun String.resolvedTextDirection(): TextDirection =
    if (isRtl()) TextDirection.Rtl else TextDirection.Ltr

fun String.resolvedTextAlign(): TextAlign =
    if (isRtl()) TextAlign.Right else TextAlign.Left
