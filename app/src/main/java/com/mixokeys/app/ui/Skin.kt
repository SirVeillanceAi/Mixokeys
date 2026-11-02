package com.mixokeys.app.ui

import androidx.compose.ui.graphics.Color

/** How the sheet music looks and moves. Picked in Settings. */
enum class Skin(
    val title: String,
    val blurb: String,
    val paper: Color,
    val ink: Color,
    val lines: Color,
    val now: Color,
    val soft: Color,
    /** true = one long line that scrolls past a fixed playhead; false = pages that turn. */
    val scroll: Boolean,
    /** Default size: dp per staff space. */
    val space: Float,
    /** Letter names inside the note heads. */
    val names: Boolean,
    /** Show one hand at a time (with a Right / Left / Both switch). */
    val oneHand: Boolean,
    /** Controls in a side rail so the music fills the screen. */
    val rail: Boolean,
    /** Clefs stay faint at the left instead of in a solid column. */
    val ghostClefs: Boolean,
    /** Dark app chrome around the music. */
    val darkCard: Boolean,
) {
    Paper(
        "Sheet paper", "Black notes on paper, scrolling past a fixed line. Lyrics sit under the notes.",
        Color(0xFFF6F3EA), Color(0xFF17161C), Color(0xFF2B2A33), Color(0xFFE0701A), Color(0xFF6E6B7A),
        scroll = true, space = 7.5f, names = false, oneHand = false, rail = false, ghostClefs = false, darkCard = false,
    ),
    Night(
        "Night", "Bright notes and lines on a dark page, easy on the eyes. No clef column.",
        Color(0xFF101018), Color(0xFFF4F2FF), Color(0x9ED6D3F0), Color(0xFFFFB23F), Color(0xFFA3A1B5),
        scroll = true, space = 7.5f, names = false, oneHand = false, rail = false, ghostClefs = true, darkCard = true,
    ),
    BigNotes(
        "One hand, big notes", "Large notes with their letter names, one hand at a time. Good for beginners.",
        Color(0xFFEEEAFB), Color(0xFF2E2850), Color(0xFF3A3355), Color(0xFFE0701A), Color(0xFF7D75A6),
        scroll = true, space = 11f, names = true, oneHand = true, rail = false, ghostClefs = false, darkCard = false,
    ),
    Pages(
        "Page mode", "Printed-music pages that turn by themselves as you play.",
        Color(0xFFF6F3EA), Color(0xFF17161C), Color(0xFF2B2A33), Color(0xFFE0701A), Color(0xFF6E6B7A),
        scroll = false, space = 7f, names = false, oneHand = false, rail = false, ghostClefs = false, darkCard = false,
    ),
    Focus(
        "Full screen", "The music fills the screen; controls move to a slim side rail. Best sideways.",
        Color(0xFFF6F3EA), Color(0xFF17161C), Color(0xFF2B2A33), Color(0xFFE0701A), Color(0xFF6E6B7A),
        scroll = true, space = 6.5f, names = false, oneHand = false, rail = true, ghostClefs = false, darkCard = false,
    );

    companion object {
        fun named(n: String?) = entries.firstOrNull { it.name == n } ?: Paper
    }
}
