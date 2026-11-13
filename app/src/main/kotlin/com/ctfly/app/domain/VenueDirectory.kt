package com.ctfly.app.domain

/** Physical areas used to group facilities in the booking catalogue. */
enum class VenueArea(
    val label: String,
    val floor: String,
) {
    GAMING("Gaming", "L1"),
    BILLIARDS("Billiards", "L2"),
    LOUNGE("Social lounge", "G"),
    OUTDOOR("Kart circuit", "G"),
}

object VenueDirectory {
    val kinds = listOf("PC", "VIPRoom", "Pool", "Snooker", "Console", "Racing", "Arcade", "GoKart")

    fun durations(kind: String) =
        when (kind) {
            "GoKart" -> listOf(30, 60)
            "Arcade" -> listOf(30, 60, 90, 120)
            else -> listOf(30, 60, 90, 120, 180)
        }

    fun area(kind: String) =
        when (kind) {
            "Pool",
            "Snooker" -> VenueArea.BILLIARDS
            "Console",
            "Racing",
            "Arcade" -> VenueArea.LOUNGE
            "GoKart" -> VenueArea.OUTDOOR
            else -> VenueArea.GAMING
        }

    fun label(kind: String) =
        when (kind) {
            "GoKart" -> "Go-kart"
            "Racing" -> "Racing simulator"
            "VIPRoom" -> "VIP room"
            else -> kind
        }
}
