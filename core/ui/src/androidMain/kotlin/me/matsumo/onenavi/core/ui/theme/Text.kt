package me.matsumo.onenavi.core.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

fun TextStyle.start() = this.merge(TextStyle(textAlign = TextAlign.Start))
fun TextStyle.center() = this.merge(TextStyle(textAlign = TextAlign.Center))
fun TextStyle.end() = this.merge(TextStyle(textAlign = TextAlign.End))

fun TextStyle.semiBold() = this.merge(TextStyle(fontWeight = FontWeight.SemiBold))
fun TextStyle.bold() = this.merge(TextStyle(fontWeight = FontWeight.Bold))
fun TextStyle.extraBold() = this.merge(TextStyle(fontWeight = FontWeight.ExtraBold))
fun TextStyle.italic() = this.merge(TextStyle(fontStyle = FontStyle.Italic))
