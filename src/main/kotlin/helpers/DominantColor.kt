package helpers

import org.imgscalr.Scalr
import java.awt.image.BufferedImage

fun dominantColorRGB(buffImg: BufferedImage): IntArray {
    val scaled = Scalr.resize(buffImg, 132)
    // get dominant color from image as array of R G B values. Ignore alpha channel
    return scaled.getRGB(0, 0, scaled.width, scaled.height, null, 0, scaled.width)
        .map { it and 0xFFFFFF }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        ?.let { intArrayOf(it shr 16, (it shr 8) and 0xFF, it and 0xFF) }
        ?: intArrayOf(0, 0, 0)
}

fun dominantColorHex(buffImg: BufferedImage): Int {
    val scaled = Scalr.resize(buffImg, 132)
    // get dominant color from image as array of R G B values. Ignore alpha channel
    val dominantColor = scaled.getRGB(0, 0, scaled.width, scaled.height, null, 0, scaled.width)
        .map { it and 0xFFFFFF }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        ?: 0

    return dominantColor
}
