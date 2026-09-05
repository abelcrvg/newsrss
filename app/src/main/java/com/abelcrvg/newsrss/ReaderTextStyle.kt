package com.abelcrvg.newsrss

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/** Local equivalent of Compose's withStyle helper, kept dependency-light. */
private fun AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    pushStyle(style)
    block()
    pop()
}
