package com.johanwes.cometdownload.player

/** A colour the subtitle panel offers, with the name a screen reader says for it. */
data class NamedColor(val name: String, val argb: Int)

/**
 * How subtitle text is drawn, shared by all videos. Subtitles that come as pictures (PGS,
 * styled ASS) are shown as they are.
 */
data class SubtitleStyle(
    val textColor: Int = TEXT_COLORS.first().argb,
    val borderColor: Int = BORDER_COLORS.first().argb,
    /** Thickness of the border in steps, 0 for none. */
    val border: Int = DEFAULT_BORDER,
) {
    companion object {
        const val MAX_BORDER = 4
        const val DEFAULT_BORDER = 2

        val TEXT_COLORS = listOf(
            NamedColor("White", 0xFFFFFFFF.toInt()),
            NamedColor("Soft white", 0xFFCFCFCF.toInt()),
            NamedColor("Yellow", 0xFFFFE23D.toInt()),
            NamedColor("Cyan", 0xFF7FE6FF.toInt()),
            NamedColor("Green", 0xFF8FF08F.toInt()),
        )
        val BORDER_COLORS = listOf(
            NamedColor("Black", 0xFF000000.toInt()),
            NamedColor("Dark grey", 0xFF3D3D3D.toInt()),
            NamedColor("White", 0xFFFFFFFF.toInt()),
            NamedColor("Dark blue", 0xFF0A2A66.toInt()),
            NamedColor("Dark red", 0xFF661010.toInt()),
        )
    }
}
