import java.io.File

fun main() {
    val titles = listOf(
        "Ed Sheeran - Shape of You [Official Video]",
        "Tum Hi Ho | Aashiqui 2 | Aditya Roy Kapur",
        "Shape of You",
        "Imagine Dragons - Believer",
        "Blinding Lights",
        "Chaleya | Jawan | Shah Rukh Khan | Nayanthara"
    )

    for (trackTitle in titles) {
        val cleanTitle = trackTitle
            .replace(Regex("(?i)\\(.*?official.*?\\)|\\[.*?official.*?\\]|\\(.*?lyric.*?\\)|\\[.*?lyric.*?\\]|\\(.*?video.*?\\)|\\[.*?video.*?\\]|\\(.*?audio.*?\\)|\\[.*?audio.*?\\]"), "")
            .replace(Regex("(?i)ft\\..*|feat\\..*"), "")
            .replace(Regex("\\|.*"), "")
            .replace(Regex(" - .*"), "")
            .replace(Regex("(?i)full video|full song|video song|audio song|lyrical video|lyrical"), "")
            .trim()
        println("Original: $trackTitle -> Clean: '$cleanTitle'")
    }
}
