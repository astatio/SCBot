package interfaces

import commands.ranks.Ranks
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.FileUpload

/**
 * Interface for the BannerHandler. This is used to handle the banner creation and special messages.
 * If you wish to handle banners but with no conditions for a special message, you can set specialMessage to return an empty string.
 */
interface BannerHandler {
	suspend fun createBanner(
		username: String,
		exp: Long,
		rankPosition: Long,
		calculate: Ranks.CalculateLevel,
		level: Long
	) : FileUpload
	suspend fun specialMessage(calculateLevel: Ranks.CalculateLevel, guild: Guild) : String
}

//todo: wouldn't it be better for the handleBanner to want an event?
// Currently, is the way as it was before this interface was created.
