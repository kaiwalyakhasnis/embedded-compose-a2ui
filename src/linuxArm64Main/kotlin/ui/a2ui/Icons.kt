package ui.a2ui

import androidx.compose.runtime.Composable
import ui.Color
import ui.dp
import ui.material.Icon

/**
 * The icons the agent can name (Material names, snake_case; camelCase is
 * converted), drawn by ui.material. Others show as a ring with the name's first letter.
 */
val A2UI_ICONS = listOf(
    "check", "close", "add", "remove", "star", "favorite", "info", "warning", "error", "home", "settings",
    "search", "mail", "event", "person", "place", "shopping_cart", "schedule", "play_arrow", "pause",
    "sunny", "cloud", "rainy", "mic", "image",
)

private val ALIASES = mapOf(
    "done" to "check", "check_circle" to "check", "cancel" to "close", "clear" to "close", "plus" to "add",
    "minus" to "remove", "favorite_border" to "favorite", "heart" to "favorite", "help" to "info",
    "calendar_today" to "event", "calendar_month" to "event", "calendar" to "event", "date_range" to "event",
    "account_circle" to "person", "user" to "person", "location_on" to "place", "pin_drop" to "place", "map" to "place",
    "cart" to "shopping_cart", "alarm" to "schedule", "timer" to "schedule", "access_time" to "schedule", "clock" to "schedule",
    "play" to "play_arrow", "wb_sunny" to "sunny", "light_mode" to "sunny", "sun" to "sunny", "wb_cloudy" to "cloud",
    "cloudy" to "cloud", "rain" to "rainy", "microphone" to "mic", "keyboard_voice" to "mic", "umbrella" to "rainy", "water_drop" to "rainy", "email" to "mail",
    "photo" to "image", "notifications" to "info", "error_outline" to "error", "report" to "warning",
)

internal fun iconName(name: String): String {
    val snake = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    return ALIASES[snake] ?: snake
}

/** A2UI's Icon: [name] in a [sizeDp] square. */
@Composable
fun A2uiIcon(name: String, color: Color, sizeDp: Int = 24) = Icon(iconName(name), size = sizeDp.dp, color = color)
