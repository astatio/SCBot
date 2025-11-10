import Guides.BASELINE_RANK_H
import Guides.BASELINE_TOTAL_XP_H
import Guides.RANK_LEFT_W
import Guides.RANK_RIGHT_W
import Guides.RIGHT_ALIGN_BUFFER
import commands.ranks.Ranks
import commands.ranks.Ranks.getAvailableRankBanners
import helpers.shorten
import interfaces.BannerHandler
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.FileUpload
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

object CustomBannerHandler : BannerHandler {
    override suspend fun createBanner(
        username: String,
        exp: Long,
        rankPosition: Long,
        calculate: Ranks.CalculateLevel,
        level: Long
    ): FileUpload {
        createBannerImage(username, exp, rankPosition, calculate, level).let {
            return it
        }
    }

    override suspend fun specialMessage(
        calculateLevel: Ranks.CalculateLevel,
        guild: Guild
    ): String {
        //This is the superdance on lvl100. it's just a different name for the function now. more special messages can be
        //added here.
        return if (calculateLevel.level >= 100 && guild.idLong == 182551016309260288L) {
            "<a:SuperDance:667049342326145025>"
        } else ""
    }
}


/**
 * The function for the level check commands. This is exclusively meant to be used by the level check commands.
 *
 * @return ByteArrayInputStream of the image
 */
private fun createBannerImage(
    username: String,
    exp: Long,
    rankPosition: Long,
    calculate: Ranks.CalculateLevel,
    level: Long
): FileUpload {

    // This is hardcoded for now. This currently gets the available banners in "resources" folder to get the role colors.
    // The intended is to get the role colors from their closest but lower level rank role.
    // The desirable line of code:
    // val closestLevel = getAllGuildRankRoles(event.guild.id).filter { it.rankLevel!! <= calculate.level }.maxByOrNull { it.rankLevel!! }?.rankLevel ?: 0
    val closestRankBanner =
        String.format("%02d", (getAvailableRankBanners().filter { it <= calculate.level }.maxByOrNull { it } ?: 0))

    val image = ImageIO.read(File("src/main/resources/rank/banners/$closestRankBanner.png"))
    val w = image.width
    val fLeftover = calculate.leftover.shorten()
    val fTotal = calculate.total.shorten()

    val vipnagorgialla: Font =
        Font.createFont(Font.TRUETYPE_FONT, File("src/main/resources/rank/vipnagorgialla.ttf"))
    val regFont = vipnagorgialla.deriveFont(50f)
    val miniFont = vipnagorgialla.deriveFont(43f)
    val bigFont = vipnagorgialla.deriveFont(56f)

    val graphics2D = image.createGraphics()

    with(graphics2D) {
        applyRenderingHints()

        fun wtext(text: String): Int {
            return font.createGlyphVector(fontRenderContext, text).outline.bounds.width
        }

        fun rightPos(text: String): Int {
            return w - RIGHT_ALIGN_BUFFER - wtext(text)
        }

        fun centerPos(text: String): Int {
            return RANK_LEFT_W + (RANK_RIGHT_W - RANK_LEFT_W) / 2 - wtext(text) / 2
        }

        font = regFont
        drawString("# $rankPosition", rightPos("# $rankPosition"), Guides.BASELINE_SCORE_H)
        font = miniFont
        drawString("$fLeftover/$fTotal", rightPos("$fLeftover/$fTotal"), Guides.BASELINE_NEXT_LEVEL_H)
        font = regFont
        drawString("$exp XP", rightPos("$exp XP"), BASELINE_TOTAL_XP_H)
        font = bigFont
        drawString(calculate.level.shorten(), centerPos(calculate.level.shorten()), BASELINE_RANK_H)
        color = Color.WHITE
        dispose()
    }

    return ByteArrayOutputStream().use {
        ImageIO.write(image, "png", it)
        ByteArrayInputStream(it.toByteArray())
    }.let {
        FileUpload.fromData(it, "${username}.png")
    }
}

private fun Graphics2D.applyRenderingHints() {
    setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE)
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}


// Create an enum with constants for the ranks
internal object Guides {
    const val BASELINE_SCORE_H = 116
    const val BASELINE_NEXT_LEVEL_H = 221
    const val BASELINE_TOTAL_XP_H = 466
    const val BASELINE_RANK_H = 725
    const val RANK_LEFT_W = 407
    const val RANK_RIGHT_W = 601
    const val RIGHT_ALIGN_BUFFER = 64
}
