package com.example.data.model

object CuratedTracks {
    val trending = listOf(
        Track(
            id = "S9O_W6y0668",
            title = "Good Days",
            artist = "SZA",
            album = "SOS",
            thumbnailUrl = "https://img.youtube.com/vi/S9O_W6y0668/maxresdefault.jpg",
            duration = "4:40"
        ),
        Track(
            id = "MSRcC6shJD4",
            title = "Kill Bill",
            artist = "SZA",
            album = "SOS",
            thumbnailUrl = "https://img.youtube.com/vi/MSRcC6shJD4/maxresdefault.jpg",
            duration = "2:33"
        ),
        Track(
            id = "LDY_XGv76kM",
            title = "Snooze",
            artist = "SZA",
            album = "SOS",
            thumbnailUrl = "https://img.youtube.com/vi/LDY_XGv76kM/maxresdefault.jpg",
            duration = "3:21"
        ),
        Track(
            id = "4NRXx6U8ABQ",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            thumbnailUrl = "https://img.youtube.com/vi/4NRXx6U8ABQ/maxresdefault.jpg",
            duration = "3:21"
        ),
        Track(
            id = "34Na43OfxLY",
            title = "Starboy",
            artist = "The Weeknd",
            album = "Starboy",
            thumbnailUrl = "https://img.youtube.com/vi/34Na43OfxLY/maxresdefault.jpg",
            duration = "3:50"
        ),
        Track(
            id = "XXYlSFmSTxs",
            title = "Save Your Tears",
            artist = "The Weeknd",
            album = "After Hours",
            thumbnailUrl = "https://img.youtube.com/vi/XXYlSFmSTxs/maxresdefault.jpg",
            duration = "3:35"
        )
    )

    val electroDuo = listOf(
        Track(
            id = "5NV6Rdv1a0w",
            title = "Get Lucky",
            artist = "Daft Punk ft. Pharrell",
            album = "Random Access Memories",
            thumbnailUrl = "https://img.youtube.com/vi/5NV6Rdv1a0w/maxresdefault.jpg",
            duration = "4:08"
        ),
        Track(
            id = "a5uQMwRMHcs",
            title = "Instant Crush",
            artist = "Daft Punk ft. Julian Casablancas",
            album = "Random Access Memories",
            thumbnailUrl = "https://img.youtube.com/vi/a5uQMwRMHcs/maxresdefault.jpg",
            duration = "5:37"
        ),
        Track(
            id = "FGBhQAkFHWY",
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery",
            thumbnailUrl = "https://img.youtube.com/vi/FGBhQAkFHWY/maxresdefault.jpg",
            duration = "5:20"
        )
    )

    val modernPop = listOf(
        Track(
            id = "H5v3kku4y6Q",
            title = "As It Was",
            artist = "Harry Styles",
            album = "Harry's House",
            thumbnailUrl = "https://img.youtube.com/vi/H5v3kku4y6Q/maxresdefault.jpg",
            duration = "2:47"
        ),
        Track(
            id = "tCXGJQYZ9JA",
            title = "Cruel Summer",
            artist = "Taylor Swift",
            album = "Lover",
            thumbnailUrl = "https://img.youtube.com/vi/tCXGJQYZ9JA/maxresdefault.jpg",
            duration = "2:58"
        ),
        Track(
            id = "b1kbLwvqugk",
            title = "Anti-Hero",
            artist = "Taylor Swift",
            album = "Midnights",
            thumbnailUrl = "https://img.youtube.com/vi/b1kbLwvqugk/maxresdefault.jpg",
            duration = "3:20"
        ),
        Track(
            id = "T6eK-mAk_Zs",
            title = "Not Like Us",
            artist = "Kendrick Lamar",
            album = "Single",
            thumbnailUrl = "https://img.youtube.com/vi/T6eK-mAk_Zs/maxresdefault.jpg",
            duration = "4:34"
        ),
        Track(
            id = "ApXoWvhIecU",
            title = "Sunflower",
            artist = "Post Malone & Swae Lee",
            album = "Spider-Man",
            thumbnailUrl = "https://img.youtube.com/vi/ApXoWvhIecU/maxresdefault.jpg",
            duration = "2:37"
        )
    )

    val allCurated = trending + electroDuo + modernPop
}
