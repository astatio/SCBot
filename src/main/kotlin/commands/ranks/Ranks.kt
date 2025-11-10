package commands.ranks

import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.*
import commands.core.Alerts.onInvalid
import commands.core.Alerts.roleCheck
import database
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.components.button
import helpers.*
import helpers.ScheduledExpiration.cancel
import helpers.ScheduledExpiration.expire
import interfaces.BannerHandler
import interfaces.MessageReplacements
import interfaces.appendRelevantReplacementsInfo
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import logger
import memberJoin
import mjson
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.TimeFormat
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

object Ranks {

    //things that use dependency injection are right here at the beginning

    private var bannerHandler: BannerHandler? = null

    /**
     * Set the banner handler for the rank system. The rank system will not work without a banner handler!
     * For more information on banner handlers, see the [BannerHandler] interface.
     *
     *  @param handler The handler to set
     *
     */
    fun setBannerHandler(handler: BannerHandler) {
        bannerHandler = handler
    }

    suspend fun getBanner(
        username: String,
        exp: Long,
        rankPosition: Long,
        calculate: CalculateLevel,
        level: Long,
        guild: Guild
    ): Pair<FileUpload?, String?> {
        //needs to return a FileUpload but also a Message, if not null
        if (bannerHandler == null) {
            logger.error {
                "setBannerHandler() has not been called yet. Suggestion: Call setBannerHandler() when starting up the bot."
            }
        }
        return Pair(
            bannerHandler?.createBanner(username, exp, rankPosition, calculate, level),
            bannerHandler?.specialMessage(calculateLevel = calculate, guild = guild)
        )
    }


    //todo: this isnt used for its intended purpose of storing cooldowns.
    // should create someting liked MemberCooldowns, UserCooldowns (affects their member counterpart), GuildCooldowns, GlobalLimitCooldowns (for commands with instance limits that need a little break)
    // The Pair should container the user ID (as Long) and the guild ID (as Long) to make up a member currently under cooldown.
    private val cooldowns: ScheduledDeque<Pair<Long, Long>> = ScheduledDeque()

    data class RankMember(
        val id: Long, //The user ID
        val exp: Long,  // Starts with 0. Long supports up to 9,223,372,036,854,775,807
        val guildId: Long
    )

    data class XPRankRoles(
        val id: Long, //The role ID
        val rankLevel: Int, //The level that the role is given at.
        val specialMessage: String?, // In case the admin wants to have a special message for this level up instead of the standard one.
        val guildId: Long
    )

    data class XPRankRoleless(
        val rankLevel: Int,
        val specialMessage: String,
        val guildId: Long
    )

    data class HappyHour(
        val status: Boolean, // If the happy hour is enabled or not. By default, it is disabled
        val percentage: Int // The percentage of XP that will be given during the happy hour. Can be between 1 and 1000. Cannot be negative or zero.
    )

    data class RankSettings(
        val status: Boolean, // If the rank system is enabled or not. By default, it is disabled
        val showMessage: Boolean, //If the message should be shown to the user when they rank up. By default, it is disabled - but is enabled if a message is set.
        val xpInterval: Int, //The interval in seconds. By default, it is 30.
        val xpUpMessage: String,
        // The message that will be sent when a user levels up.
        // By default, is "Congratulations, {user}! You ranked up to Rank {level}!"
        val xpPerMessage: Int, //The amount of xp per message. By default, it is 10.
        val happyHour: HappyHour,
        val ignoredChannels: List<Long>, // Channels were messages should not count towards the rank
        val ignoredRoles: List<Long>, // Roles where messages should not count towards the rank
        val guildId: Long
    ) {
        companion object {
            fun create(
                status: Boolean = false,
                showMessage: Boolean = false,
                xpInterval: Int = 30,
                xpUpMessage: String = "Congratulations, {user}! You ranked up to Rank {level}!",
                xpPerMessage: Int = 10,
                happyHour: HappyHour = HappyHour(false, 0),
                ignoredChannels: List<Long> = emptyList(),
                ignoredRoles: List<Long> = emptyList(),
                guildId: Long
            ) = RankSettings(
                status,
                showMessage,
                xpInterval,
                xpUpMessage,
                xpPerMessage,
                happyHour,
                ignoredChannels,
                ignoredRoles,
                guildId
            )
        }
    }

    val ranksCollection = database.getCollection<RankSettings>("rankSettings")
    val ranksMemberCollection = database.getCollection<RankMember>("rankMember")
    val ranksXPRankRolesCollection = database.getCollection<XPRankRoles>("xprankRoles")
    val ranksXPRankRolelessCollection = database.getCollection<XPRankRoleless>("xprankRoleless")

    suspend fun getRankSettings(guildId: Long) =
        ranksCollection.find(eq(RankSettings::guildId.name, guildId)).firstOrNull()

    /**
     * Provide a user's XP and get the closest rank role ID available in the guilds rank settings
     *
     * @return The role ID of the closest rank role
     */
    suspend fun getClosestAvailableRankID(user: User, guildId: Long): Long? {
        val memberXP =
            ranksMemberCollection.find(
                and(
                    eq(RankMember::id.name, user.idLong),
                    eq(RankMember::guildId.name, guildId)
                )
            ).firstOrNull() ?: return null
        val rankRls = ranksXPRankRolesCollection.find(eq(XPRankRoles::guildId.name, guildId)).toList()
        // get only the closest but lower or equal rank role
        val currentlevel = CalculateLevel(memberXP.exp).level
        val closestRankRole = rankRls.filter { it.rankLevel <= currentlevel }.maxByOrNull { it.rankLevel }
        return closestRankRole?.id
    }

    /**
     * Returns a list of the rank roles that the member has.
     * If the member has no rank roles, it will return an empty list.
     *
     * Using this creates a temporary cache to avoid multiple database calls.
     */
    suspend fun getRankRoles(member: Member): List<Role> {
        val guildRoles = ranksXPRankRolesCollection.find(eq(XPRankRoles::guildId.name, member.guild.idLong)).toList()
        return member.roles.filter { role -> guildRoles.any { it.id == role.idLong } }
    }

    /**
     * Message replacement method used for rank level up.
     * Replaces {user}, {level} by default.
     *
     * Any other replacements can be added by implementing RankMessageReplacement and calling Ranks.registerReplacements()
     *
     * e.g.: {dog} -> can be replaced by "" and add an URL to the message corresponding to a random image of a dog.
     *
     *
     * @param message
     * @param level the member current level as a string
     * @param event
     * @return the message with the proper replacements
     */
    private fun messageReplacement(message: String, level: String, event: MessageReceivedEvent): String {
        var result = MessageReplacements.applyReplacements(message, event)
        if (result.contains("{level}")) {
            result = result.replace("{level}", level)
        }
        return result
    }


    /**
     * Gets the member exp. If the member does not exist, or it's XP is null, it will return 0.
     * The member won't be created if it doesn't exist.
     *
     * @param memberId The member id
     * @param guildId The guild id
     * @return The member exp or zero if not found
     */
    suspend fun getMemberExp(memberId: Long, guildId: Long) = ranksMemberCollection.find(
        and(
            eq(RankMember::id.name, memberId),
            eq(RankMember::guildId.name, guildId)
        )
    ).firstOrNull()?.exp ?: 0

    /**
     * Gets all the members exp of a guild as represented in the database. **This does not check if the members are actually still in the Discord guild**, and as such, it will show past members exp.
     * Current Discord members that have not gained any exp will not count.
     *
     * @param guildId The guild id
     * @return The members exp in a list, without the member ids
     */
    suspend fun getAllMembersExps(guildId: Long) =
        ranksMemberCollection.find(eq(RankMember::guildId.name, guildId)).map { it.exp }.toList()

    /**
     * Get available rank banners stored in src/main/resources/rank/banners/
     *
     * **Warning**: It will only get the rank banners with a png extension
     * @throws NumberFormatException if the filename is not a valid representation of a number
     *
     * @return A list of integers containing the available rank banners without extension (e.g. [00, 05, 10, 20])
     */
    fun getAvailableRankBanners(): List<Int> {
        return File("src/main/resources/rank/banners/")
            .walkTopDown()
            .filter { it.extension == "png" }
            .map { it.nameWithoutExtension.toInt() }
            .toList()
    }


    /**
     * Get the rank position of a member in a guild. It's sorted descending by exp.
     *
     * @param user The user (that should be a member) to get the rank position of
     * @param guildId The guild ID to get the rank position of the user in
     * @param memberXP  The member's exp. If not provided, it will get the member's exp from the database
     *
     * @return The rank position of the member
     */
    suspend fun getRankPosition(user: User, guildId: Long, memberXP: Long? = null): Long {
        val memberExp = memberXP ?: getMemberExp(user.idLong, guildId)
        val exps = getAllMembersExps(guildId)

        // Sort the exps from biggest to smallest. Previously, this used toSet() due to its description
        // stating that "The returned set preserves the element iteration order of the original sequence."
        // but a List will preserve the order of insertion by default so theres no need to use Sets
        val sortedExp = exps.sortedDescending()
        // Get the index of the member's exp in the sorted list
        return sortedExp.indexOf(memberExp).toLong() + 1 // +1 because the index starts at 0
    }

    /**
     * Utility class to perform various calculations on the member's exp
     * Can calculate the current level, the exp needed to reach the next level, and the total exp in the current level
     *
     * @param exp The member's exp
     */
    class CalculateLevel(exp: Long) {
        val level: Long
        val leftover: Long
        val total: Long

        init {
            var currentLevel = 0.0
            var remainingExp = exp

            while (remainingExp >= (5 * currentLevel.pow(2) + 50 * currentLevel + 100).toLong()) {
                remainingExp -= (5 * currentLevel.pow(2) + 50 * currentLevel + 100).toLong()
                currentLevel++
            }

            level = currentLevel.toLong()
            total = (5 * currentLevel.pow(2) + 50 * currentLevel + 100).toLong()
            leftover = remainingExp
        }
    }


    /**
     * Calculate the exp needed to reach the provided level.
     *
     * @param level The level to calculate the exp for
     * @return The total exp needed to reach the level
     */
    fun calculateExp(level: Int): Long {
        return (0 until level).sumOf { i ->
            (5 * i.toDouble().pow(2) + 50 * i + 100).toLong()
        }
    }

    suspend fun levelCheckMessageVersion(event: MessageReceivedEvent, mentionedMember: Member? = null) {
        val member = mentionedMember ?: event.member!!
        val exp = getMemberExp(member.idLong, event.guild.idLong)
        val calculate = CalculateLevel(exp)
        val rankPosition = getRankPosition(member.user, event.guild.idLong)


        //the old fileUpload had the exact same arguments
        val bannerMessage = getBanner(event.author.name, exp, rankPosition, calculate, calculate.level, event.guild)
        //the following lines needs adaption. bannerMessage is a pair with fileupload in it and a nullable string for the message.
        val reply = event.message.replyFiles(bannerMessage.first).mentionRepliedUser(false)
        if (bannerMessage.second != null) {
            reply.setContent(bannerMessage.second)
        }
        reply.queue()
    }

    suspend fun levelCheckContextVersion(event: UserContextInteractionEvent, target: User) {
        if (!event.isFromGuild || !event.channelType.isMessage) {
            if (!event.channelType.isMessage) {
                event.user.openPrivateChannel().queue {
                    it.sendMessage("The command you are trying to use can only be used in a server and in a message channel.")
                        .queue()
                }
                return
            }
            event.hook.sendMessage("This command can only be used in a server and in a message channel.")
                .setEphemeral(true).queue()
            return
        }
        val guild = event.guild!!

        guild.retrieveMember(target).let {
            val exp = getMemberExp(target.idLong, guild.idLong)
            val calculate = CalculateLevel(exp)
            val rankPosition = getRankPosition(target, guild.idLong)

            val banner = getBanner(event.target.name, exp, rankPosition, calculate, calculate.level, event.guild!!)
            val reply = event.hook.sendFiles(banner.first).mentionRepliedUser(false)
            if (banner.second != null) {
                reply.setContent(banner.second)
            }
            reply.queue()
        }
    }


    @OptIn(ExperimentalTime::class)
    suspend fun leaderboard(event: MessageReceivedEvent) {
        //only allow two instances of this at a time per guild
        // bot developers should be able to define their own limits for this command, and if not defined should use this default limit.
        //todo: this is missing tracking of this command usage.
        if (Command.LEADERBOARD.isOnCooldown(event.member!!.idLong, event.guild.idLong)) {
            event.message.sendRLmessage()
            return
        }

        val top100 = LeaderboardHelper.getTopMembers(event.guild.idLong)
        val currentPage = AtomicInteger(1)
        val pairedList = top100.withIndex().map {
            "${it.index + 1}. ${
                event.jda.retrieveUserById(it.value.id).useCache(true).await().name
            }" to "${it.value.exp.withWhitespaces()} XP"
        }

        val navEmbed = NavigableEmbed()

        val embed = navEmbed.createEmbed(
            embedTitle = "Leaderboard",
            event.channel,
            pairedList,
            currentPage = currentPage
        )

        val buttonCallbacks = navEmbed.createButtonCallbacks(event.channel, pairedList, currentPage)
        val buttons = navEmbed.createButtons(event.jda, buttonCallbacks)

        event.channel.sendMessage(
            MessageCreateBuilder()
                .addEmbeds(embed.build())
                .setContent(
                    "-# ⓘ This leaderboard will expire ${
                        TimeFormat.RELATIVE.format(
                            Clock.System.now().plus(5.minutes).toEpochMilliseconds()
                        )
                    }"
                )
                .setComponents(ActionRow.of(buttons))
                .build()
        ).queue {
            it.expire(5.minutes)
        }
        // I do not know if this delay is truly needed.
        // If people's interactions start to fail, remove the slashes from the comment below.
        //delay(5.minutes)
    }

    suspend fun switch(event: SlashCommandInteractionEvent, mode: Boolean) {
        val guildId = event.guild!!.idLong
        event.sendConfirmationPrompt(
            description = "This is a delicate module. Are you sure you want to ${if (mode) "enable" else "disable"} the rank system?"
        ) { _ ->
            ranksCollection.updateOne(
                filter = eq(RankSettings::guildId.name, guildId),
                update = combine(
                    set(RankSettings::status.name, mode),
                    setOnInsert(RankSettings::showMessage.name, false),
                    setOnInsert(RankSettings::xpInterval.name, 30),
                    setOnInsert(
                        RankSettings::xpUpMessage.name,
                        "Congratulations, {user}! You ranked up to Rank {level}!"
                    ),
                    setOnInsert(RankSettings::xpPerMessage.name, 10),
                    setOnInsert(RankSettings::happyHour.name, HappyHour(false, 0)),
                    setOnInsert(RankSettings::ignoredChannels.name, emptyList<Long>()),
                    setOnInsert(RankSettings::ignoredRoles.name, emptyList<Long>()),
                    setOnInsert(RankSettings::guildId.name, guildId)
                ),
                options = UpdateOptions().upsert(true)
            )
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "**Ranks** is now ${if (mode) "enabled" else "disabled"}"
            }
        }
    }

    suspend fun addSpecialRankRole(
        event: SlashCommandInteractionEvent,
        rank: Int,
        message: String?,
        role: Role
    ) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong
        // Check if there's already a special "roleless" message for this rank. If there is, don't allow proceeding.
        ranksXPRankRolelessCollection.find(
            and(
                eq(XPRankRoleless::guildId.name, guildId),
                eq(XPRankRoleless::rankLevel.name, rank)
            )
        ).firstOrNull().let {
            if (it != null) {
                event.hook.sendMessageEmbeds(NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text =
                        "There's already a special roleless message for this rank. Please remove it first and try again."
                }).queue()
                return
            }
        }

        ranksXPRankRolesCollection.updateOne(
            filter = and(
                eq(XPRankRoles::guildId.name, guildId),
                eq(XPRankRoles::rankLevel.name, rank)
            ),
            update = combine(
                set(XPRankRoles::id.name, role.idLong),
                setOnInsert(XPRankRoles::rankLevel.name, rank),
                set(XPRankRoles::specialMessage.name, message),
                setOnInsert(XPRankRoles::guildId.name, guildId)
            ),
            options = UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Added successfully!"
        }).queue()
    }

    //TODO: I was here in fixing database driver migration alongside the update change.

    suspend fun addSpecialRankRoleless(
        event: SlashCommandInteractionEvent,
        rank: Int,
        message: String
    ) {
        // When a certain rank is hit but there is no intention to give a new role.
        // This is for when you want to give a special message but not a role.
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }


        val guildId = event.guild!!.idLong
        // Check if there's already a role for this rank. If there is, don't allow proceeding.
        ranksXPRankRolesCollection.find<XPRankRoles>(
            and(
                eq(XPRankRoles::guildId.name, guildId),
                eq(XPRankRoles::rankLevel.name, rank)
            )
        ).firstOrNull()?.let {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "There's already a role for this rank. Please remove it first and try again."
            }).queue()
            return
        }
        ranksXPRankRolelessCollection.updateOne(
            filter = and(
                eq(XPRankRoleless::guildId.name, guildId),
                eq(XPRankRoleless::rankLevel.name, rank)
            ),
            update = combine(
                setOnInsert(XPRankRoleless::rankLevel.name, rank),
                set(XPRankRoleless::specialMessage.name, message),
                setOnInsert(XPRankRoleless::guildId.name, guildId)
            ),
            options = UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Added successfully!"
        }).queue()
    }

    suspend fun setUserXP(event: SlashCommandInteractionEvent, xp: Long, user: User) {
        // Set rank manually. It changes their rank, it does not add or remove XP.
        // needs to include a warning like:
        // "The users XP will be **decreased/increased** by 232XP and will be set to 1245XP.
        // Are you sure you want to proceed?" and show buttons
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        if (xp < 0) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "You cannot set a negative XP value"
            }).queue()
            return
        }
        val rankMember =
            ranksMemberCollection.find(
                and(
                    eq(RankMember::id.name, user.idLong),
                    eq(RankMember::guildId.name, event.guild!!.idLong)
                )
            ).firstOrNull()
        if (rankMember == null) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "There's no data for this user - therefore, you can't give them XP."
            }).queue()
            return
        }
        val oldXP = rankMember.exp
        val previousCalc = CalculateLevel(oldXP)
        val newCalc = CalculateLevel(xp)
        // find the difference between the 2 values. abs can be used for that.
        val differenceXP = xp - oldXP
        val differenceLevel = previousCalc.level - newCalc.level

        // If it is negative, it means the user is losing XP.
        // if its 0, return.
        if (differenceXP == 0L) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "The user's XP is already at the desired level."
            }).queue()
            return
        }
        val sign = if (differenceXP < 0) {
            "decrease"
        } else {
            "increase"
        }
        val levelStatement = if (differenceLevel == 0L) {
            "not change and will remain at ${previousCalc.level}."
        } else {
            "will go from ${previousCalc.level} to ${newCalc.level}."
        }

        val `continue` = event.jda.button(
            label = "Continue", style = ButtonStyle.DANGER, user = event.user, expiration = 1.minutes
        ) { buttonInteractionEvent ->
            ranksMemberCollection.updateOne(
                filter = and(
                    eq(RankMember::id.name, user.idLong),
                    eq(RankMember::guildId.name, event.guild!!.idLong)
                ),
                update = combine(
                    set(RankMember::exp.name, xp)
                )
            )
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Successfully set ${user.asMention} XP to $xp"
            }).queue()
            buttonInteractionEvent.message.expire()
        }
        val cancel = event.jda.button(
            label = "Cancel", style = ButtonStyle.SECONDARY, user = event.user, expiration = 1.minutes
        ) { it.cancel() }
        event.hook.sendMessage("The users XP will **$sign** by ${abs(differenceXP)} XP and will be set to $xp XP.\n" + "Their level will $levelStatement" + "\nAre you sure you want to proceed? Click within the next 30 seconds to confirm.")
            .setComponents(ActionRow.of(`continue`, cancel))
            .queue { msg -> msg.expire(1.minutes) }
    }

    suspend fun setLevelUpMessage(event: SlashCommandInteractionEvent, message: String) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong
        //set the message that is sent when a user levels up

        ranksCollection.updateOne(
            filter = eq(RankSettings::guildId.name, guildId),
            update = combine(
                setOnInsert(RankSettings::status.name, false),
                setOnInsert(RankSettings::xpInterval.name, 30),
                set(RankSettings::showMessage.name, true),
                set(RankSettings::xpUpMessage.name, message),
                setOnInsert(RankSettings::xpPerMessage.name, 10),
                setOnInsert(RankSettings::happyHour.name, HappyHour(false, 0)),
                setOnInsert(RankSettings::ignoredChannels.name, emptyList<Long>()),
                setOnInsert(RankSettings::ignoredRoles.name, emptyList<Long>()),
                setOnInsert(RankSettings::guildId.name, guildId)
            ),
            UpdateOptions().upsert(true)
        )

        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Level-up message set successfully!"
        }).queue()
    }

    suspend fun switchLUPMessage(event: SlashCommandInteractionEvent, mode: Boolean) {
        val guildId = event.guild!!.idLong
        ranksCollection.updateOne(
            filter = eq(RankSettings::guildId.name, guildId),
            update = combine(
                setOnInsert(RankSettings::status.name, false),
                set(RankSettings::showMessage.name, mode),
                setOnInsert(RankSettings::xpInterval.name, 30),
                setOnInsert(RankSettings::xpUpMessage.name, "Congratulations, {user}! You ranked up to Rank {level}!"),
                setOnInsert(RankSettings::xpPerMessage.name, 10),
                setOnInsert(RankSettings::happyHour.name, HappyHour(false, 0)),
                setOnInsert(RankSettings::ignoredChannels.name, emptyList<Long>()),
                setOnInsert(RankSettings::ignoredRoles.name, emptyList<Long>()),
                setOnInsert(RankSettings::guildId.name, guildId)
            )
        )
        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "**Ranks** level-up messages are now ${if (mode) "enabled" else "disabled"}"
        }).queue()
    }

    suspend fun setXPGain(event: SlashCommandInteractionEvent, xp: Int, cooldown: Int) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        //set the amount of XP gained when a user levels up
        // xp needs to be Min: 1 | Max: 1000
        // cooldown needs to be Min: 1 | Max: 120
        if (xp !in 1..1000) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "XP needs to be between 1 and 1000"
            }).queue()
            return
        }
        if (cooldown !in 1..120) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Cooldown needs to be between 1 and 120"
            }).queue()
            return
        }

        val guildId = event.guild!!.idLong
        ranksCollection.updateOne(
            filter = eq(RankSettings::guildId.name, guildId),
            update = combine(
                setOnInsert(RankSettings::status.name, false),
                setOnInsert(RankSettings::showMessage.name, false),
                setOnInsert(RankSettings::xpUpMessage.name, "Congratulations, {user}! You ranked up to Rank {level}!"),
                set(RankSettings::xpPerMessage.name, xp),
                set(RankSettings::xpInterval.name, cooldown),
                setOnInsert(RankSettings::happyHour.name, HappyHour(false, 0)),
                setOnInsert(RankSettings::ignoredChannels.name, emptyList<Long>()),
                setOnInsert(RankSettings::ignoredRoles.name, emptyList<Long>()),
                setOnInsert(RankSettings::guildId.name, guildId)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "XP gain now set to $xp!\nCooldown now set to $cooldown second(s)!"
        }).queue()
    }

    suspend fun addIgnoreRole(event: SlashCommandInteractionEvent, role: Role) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        //add a role to the list of roles that do not gain XP
        val guildId = event.guild!!.idLong
        try {
            ranksCollection.find(eq(RankSettings::guildId.name, guildId)).firstOrNull().let {
                if (it != null) {
                    if (it.ignoredRoles.contains(role.idLong)) {
                        event.hook.sendMessageEmbeds(NeoSuperEmbed {
                            type = SuperEmbed.ResultType.SIMPLE_ERROR
                            text = "The role is already ignored!"
                        }).queue()
                        return
                    }
                }
            }

            ranksCollection.updateOne(
                filter = eq(RankSettings::guildId.name, guildId),
                update = combine(
                    setOnInsert(RankSettings::status.name, false),
                    setOnInsert(RankSettings::showMessage.name, false),
                    setOnInsert(
                        RankSettings::xpUpMessage.name,
                        "Congratulations, {user}! You ranked up to Rank {level}!"
                    ),
                    setOnInsert(RankSettings::xpPerMessage.name, 10),
                    setOnInsert(RankSettings::xpInterval.name, 30),
                    setOnInsert(RankSettings::happyHour.name, HappyHour(false, 0)),
                    setOnInsert(RankSettings::ignoredChannels.name, emptyList<Long>()),
                    setOnInsert(RankSettings::ignoredRoles.name, emptyList<Long>()),
                    setOnInsert(RankSettings::guildId.name, guildId),
                    addToSet(RankSettings::ignoredRoles.name, role.idLong)
                ),
                UpdateOptions().upsert(true)
            )

            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Added the role to ignored roles!"
            }).queue()
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
            return
        }
    }

    suspend fun addIgnoreChannel(event: SlashCommandInteractionEvent, channel: GuildChannel) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        //add a channel to the list of channels that do not gain XP
        val guildId = event.guild!!.idLong
        ranksCollection.find(eq(RankSettings::guildId.name, guildId)).firstOrNull().let {
            if (it != null) {
                if (it.ignoredChannels.contains(channel.idLong)) {
                    event.hook.sendMessageEmbeds(NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "The channel is already ignored!"
                    }).queue()
                    return
                }
            }
        }
        ranksCollection.updateOne(
            filter = eq(RankSettings::guildId.name, guildId),
            update = combine(
                setOnInsert(RankSettings::status.name, false),
                setOnInsert(RankSettings::showMessage.name, false),
                setOnInsert(RankSettings::xpUpMessage.name, "Congratulations, {user}! You ranked up to Rank {level}!"),
                setOnInsert(RankSettings::xpPerMessage.name, 10),
                setOnInsert(RankSettings::xpInterval.name, 30),
                setOnInsert(RankSettings::happyHour.name, HappyHour(false, 0)),
                setOnInsert(RankSettings::ignoredChannels.name, emptyList<Long>()),
                setOnInsert(RankSettings::ignoredRoles.name, emptyList<Long>()),
                setOnInsert(RankSettings::guildId.name, guildId),
                addToSet(RankSettings::ignoredChannels.name, channel.idLong)
            ),
            UpdateOptions().upsert(true)
        )

        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Added the channel to ignored channels!"
        }).queue()
    }

    suspend fun removeSpecialRank(event: SlashCommandInteractionEvent, rank: Int) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        //remove a special rank from the list of special ranks
        val deletionA = ranksXPRankRolesCollection.deleteOne(
            and(
                eq(XPRankRoles::guildId.name, event.guild!!.idLong),
                eq(XPRankRoles::rankLevel.name, rank)
            )
        ).deletedCount
        val deletionB = ranksXPRankRolelessCollection.deleteOne(
            and(
                eq(XPRankRoleless::guildId.name, event.guild!!.idLong),
                eq(XPRankRoleless::rankLevel.name, rank)
            )
        ).deletedCount
        if (deletionA == 0L && deletionB == 0L) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "The rank is not a special rank!"
            }).queue()
            return
        }
        if (deletionA == 1L) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Removed special rank role successfully!"
            }).queue()
            return
        }
        if (deletionB == 1L) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Removed special rank roleless successfully!"
            }).queue()
            return
        }
    }

    suspend fun removeIgnoreRole(event: SlashCommandInteractionEvent, role: Role) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        //remove a role from the list of roles that do not gain XP
        val guildId = event.guild!!.idLong
        val changeCount = ranksCollection.updateOne(
            filter = eq(RankSettings::guildId.name, guildId),
            update = pull(RankSettings::ignoredRoles.name, role.idLong)
        ).modifiedCount

        if (changeCount == 0L) {
            // If the document doesn't exist, insert the default values
            ranksCollection.insertOne(RankSettings.create(guildId = guildId))
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "The role is not ignored!"
            }).queue()
            return
        }
        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Removed the role from ignored roles!"
        }).queue()
    }

    suspend fun removeIgnoreChannel(event: SlashCommandInteractionEvent, channel: GuildChannel) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        //remove a channel from the list of channels that do not gain XP
        val guildId = event.guild!!.idLong
        val modifiedCount = ranksCollection.updateOne(
            filter = eq(RankSettings::guildId.name, guildId),
            update = pull(RankSettings::ignoredChannels.name, channel.idLong)
        ).modifiedCount

        if (modifiedCount == 0L) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "There are no rank settings for this guild!"
            }).queue()
            return
        }
        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Removed the channel from ignored channels!"
        }).queue()
    }

    suspend fun getXP(event: SlashCommandInteractionEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        //get the XP gain of the guild
        val guildId = event.guild!!.idLong
        ranksCollection.find(
            eq(RankSettings::guildId.name, guildId)
        ).firstOrNull()?.let {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "`${it.xpPerMessage}` XP per message\n${it.xpInterval} seconds between messages." +
                        if (it.happyHour.status) {
                            "\n\nHappy Hour is active. `${(it.xpPerMessage * it.happyHour.percentage) / 100}` XP per message while it persists."
                        } else {
                            ""
                        }
            }).queue()
        } ?: event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SIMPLE_ERROR
            text = "There are no rank settings for this guild!"
        }).queue()
    }

    suspend fun getMessage(event: SlashCommandInteractionEvent) {
        //get the message that is sent when a user gains XP
        ranksCollection.find(
            eq(RankSettings::guildId.name, event.guild!!.idLong)
        ).firstOrNull()?.let {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "The message is: `${it.xpUpMessage}`" +
                        appendRelevantReplacementsInfo(
                            it.xpUpMessage,
                            event.toWrapper()
                        )
                if (it.xpUpMessage.isEmpty()) {
                    text = "There is no message set!"
                }
            }).queue()
        } ?: event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SIMPLE_ERROR
            text = "There are no rank settings for this server!"
        }).queue()
    }

    suspend fun getSpecialRank(event: SlashCommandInteractionEvent) {
        //get the special ranks of the guild
        val guildId = event.guild!!.idLong
        // if its empty then there are no special ranks.
        val xpRoles = ranksXPRankRolesCollection.find(
            eq(XPRankRoles::guildId.name, guildId)
        ).toList()
        val xpRoleless = ranksXPRankRolelessCollection.find(
            eq(XPRankRoleless::guildId.name, guildId)
        ).toList()

        if (xpRoles.isEmpty() && xpRoleless.isEmpty()) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "There are no special ranks for this guild!"
            }).queue()
            return
        }
        val files = mutableListOf<FileUpload>()

        // Add files for xpRoles and xpRoleless if they are not empty
        listOf(
            xpRoles to "specialranks.json",
            xpRoleless to "specialranks_roleless.json"
        ).forEach { (data, fileName) ->
            if (data.isNotEmpty()) {
                val jsonElement = mjson.parseToJsonElement(data.toString())
                val prettyJson = mjson.encodeToString(jsonElement)
                files.add(
                    FileUpload.fromData(
                        ByteArrayInputStream(prettyJson.toByteArray(Charsets.UTF_8)),
                        fileName
                    )
                )
            }
        }

        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SIMPLE
            text = "I've prepared a JSON file with the information you requested!"
        }).addFiles(files).queue()
    }

    suspend fun getIgnoreRole(event: SlashCommandInteractionEvent) {
        //get the ignored roles
        ranksCollection.find(
            eq(RankSettings::guildId.name, event.guild!!.idLong)
        ).firstOrNull()?.let {
            val roleMentionList = mutableListOf<String>()
            it.ignoredRoles.forEach { ignoredRoleId ->
                event.guild!!.getRoleById(ignoredRoleId)?.asMention?.let { role ->
                    roleMentionList.add(role)
                }
            }
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Here's the list of ignored roles: ${roleMentionList.joinToString(" ")}"
            }).queue()
        } ?: event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SIMPLE_ERROR
            text = "There are no rank settings for this guild!"
        }).queue()
    }

    suspend fun getIgnoreChannel(event: SlashCommandInteractionEvent) {
        //get the ignored channels
        ranksCollection.find(
            eq(RankSettings::guildId.name, event.guild!!.idLong)
        ).firstOrNull()?.let {
            val channelMentionList = mutableListOf<String>()
            it.ignoredChannels.forEach { ignoredChannelId ->
                event.guild!!.getGuildChannelById(ignoredChannelId)?.asMention?.let { channel ->
                    channelMentionList.add(channel)
                }
            }
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Here's the list of ignored channels: ${channelMentionList.joinToString(" ")}"
            }).queue()
        } ?: event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SIMPLE_ERROR
            text = "There are no rank settings for this guild!"
        }).queue()
    }

    suspend fun getUserInfo(event: SlashCommandInteractionEvent, userId: Long) {
        ranksMemberCollection.find(
            and(
                eq(RankMember::guildId.name, event.guild!!.idLong),
                eq(RankMember::id.name, userId)
            )
        ).firstOrNull()?.let {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "${event.guild!!.getMemberById(it.id)?.asMention ?: it.id} has ${it.exp} XP and is level ${
                    CalculateLevel(it.exp).level
                }"
            }).queue()
        } ?: event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SIMPLE_ERROR
            text = "There's no data for this user!"
        }).queue()
    }

    suspend fun giveRankLevel(event: SlashCommandInteractionEvent, level: Int, user: User) {
        // This is just like setUserXP, but it's for giving a user a specific rank level
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        if (level < 0) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "You cannot give a negative level value"
            }).queue()
            return
        }
        val memberInDB = ranksMemberCollection.find(
            and(
                eq(RankMember::id.name, user.idLong),
                eq(RankMember::guildId.name, event.guild!!.idLong)
            )
        ).firstOrNull() ?: run {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "There's no data for this user - therefore, you can't give XP to them."
            }).queue()
            return
        }

        val oldXP = memberInDB.exp
        val previousCalc = CalculateLevel(oldXP)
        val newExp = calculateExp(level)
        val differenceXP = newExp - previousCalc.total
        val differenceLevel = previousCalc.level - level

        // Check if the difference is positive or negative. If it is negative, it means the user is losing XP.
        // if its 0, return.
        if (differenceXP == 0L) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "This user is already level $level"
            }).queue()
            return
        }
        val sign = if (differenceXP < 0) "decrease" else "increase"
        val levelStatement = if (differenceLevel == 0L) {
            "not change and will remain at ${previousCalc.level}."
        } else {
            "will go from ${previousCalc.level} to ${level}."
        }

        val `continue` = event.jda.button(
            label = "Continue", style = ButtonStyle.DANGER, user = event.user, expiration = 1.minutes
        ) { interactionEvent ->
            ranksMemberCollection.updateOne(
                filter = and(
                    eq(RankMember::id.name, user.idLong),
                    eq(RankMember::guildId.name, event.guild!!.idLong)
                ),
                update = set(RankMember::exp.name, newExp),
                UpdateOptions().upsert(true)
            )
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Successfully set ${user.asMention} XP to $newExp"
            }).queue()
            interactionEvent.message.expire()
        }
        val cancel = event.jda.button(
            label = "Cancel", style = ButtonStyle.SECONDARY, user = event.user, expiration = 1.minutes
        ) { interactionEvent -> interactionEvent.cancel() }
        event.hook.sendMessage(
            "The users XP will **$sign** by ${abs(differenceXP)} XP and will be set to $newExp XP.\n" + "Their level will $levelStatement" + " \nAre you sure you want to proceed? Click within 1 minute to confirm."
        ).setComponents(ActionRow.of(`continue`, cancel)).queue { msg -> msg.expire(1.minutes) }
    }

    suspend fun giveXP(event: SlashCommandInteractionEvent, xp: Long, user: User) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        // give xp to a user
        val guildId = event.guild!!.idLong
        ranksMemberCollection.find(
            and(
                eq(RankMember::guildId.name, guildId),
                eq(RankMember::id.name, user.idLong)
            )
        ).firstOrNull()?.let {
            val previousLevel = CalculateLevel(it.exp).level
            ranksMemberCollection.updateOne(
                filter = and(
                    eq(RankMember::id.name, user.idLong),
                    eq(RankMember::guildId.name, guildId)
                ),
                update = set(RankMember::exp.name, it.exp + xp)
            )
            val newLevel = CalculateLevel(it.exp + xp).level
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = buildString {
                    append("Added $xp XP to ${user.asMention}! Now they have ${it.exp + xp} XP")
                    if (newLevel > previousLevel) {
                        append(" and went up to level $newLevel")
                    }
                    append("!")
                }
            }).queue()
        } ?: event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SIMPLE_ERROR
            text = "There's no data for this user - therefore, you can't give them XP."
        }).queue()
    }

    suspend fun startHappyhour(event: SlashCommandInteractionEvent, percentage: Int) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        if (percentage < 1 || percentage > 1000) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "The percentage needs to be between 1 and 1000"
            }).queue()
            return
        }
        // This value can't be negative or zero, and can't be higher than 1000.
        val guildId = event.guild!!.idLong
        ranksCollection.findOneAndUpdate(
            filter = eq(RankSettings::guildId.name, guildId),
            update = combine(
                set(RankSettings::status.name, true),
                set(RankSettings::happyHour.name, HappyHour(status = true, percentage = percentage))
            ),
            FindOneAndUpdateOptions().upsert(false)
        )?.let {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text =
                    "Happyhour started successfully!\nMessages will now give **${percentage}%** more XP!\nThis means that currently, messages will give **${((it.xpPerMessage * percentage) / 100)}** XP!"
            }).queue()
        } ?: event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SIMPLE_ERROR
            text = "There are no rank settings for this guild!"
        }).queue()
    }

    suspend fun stopHappyhour(event: SlashCommandInteractionEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong
        ranksCollection.findOneAndUpdate(
            filter = eq(RankSettings::guildId.name, guildId),
            update = set(RankSettings::happyHour.name, HappyHour(status = false, percentage = 0)),
            FindOneAndUpdateOptions().upsert(false)
        )?.let {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Happyhour stopped successfully!\nMessages will now give ${it.xpPerMessage} XP!"
            }).queue()
        } ?: run {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "There are no rank settings for this guild!"
            }).queue()
            return
        }
    }

    suspend fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) { //if it's a bot or isn't from a guild
            return
        }
        val authorId = event.author.idLong
        val guildId = event.guild.idLong
        val rankSettings = ranksCollection.find(
            and(
                eq(RankSettings::guildId.name, guildId),
                eq(RankSettings::status.name, true)
            )
        ).firstOrNull() ?: return //if there are no settings, return
        if (rankSettings.ignoredChannels.contains(event.channel.idLong) || event.member?.roles?.any {
                rankSettings.ignoredRoles.contains(it.idLong)
            } == true || cooldowns.deque.contains(Pair(authorId, guildId))) {
            // if the message isn't from an ignored channels,
            // if the member doesn't have an ignored role
            // if the user hasn't gained XP in the last X seconds (is on a cooldown)
            return
        }
        // the user attends all conditions to rank up with this message.

        // The cooldown logic for each user.
        // Uses a Deque to store the IDs at the bottom of the deque.
        // A scheduled executor deletes each element in the top 15 seconds after being added.

        cooldowns.addScheduled(Pair(authorId, guildId), rankSettings.xpInterval.toLong())
        val exp = getMemberExp(authorId, guildId)
        val calc = CalculateLevel(exp)
        val newExp =
            if (rankSettings.happyHour.status) {
                exp + ((rankSettings.xpPerMessage * rankSettings.happyHour.percentage) / 100)
            } else {
                exp + rankSettings.xpPerMessage
            }
        val newCalc = CalculateLevel(newExp)
        ranksMemberCollection.updateOne(
            filter = and(
                eq(RankMember::id.name, authorId),
                eq(RankMember::guildId.name, guildId)
            ),
            update = combine(
                setOnInsert(RankMember::id.name, authorId),
                setOnInsert(RankMember::guildId.name, guildId),
                set(RankMember::exp.name, newExp)
            ),
            options = UpdateOptions().upsert(true)
        )

        if (newCalc.level > calc.level) {
            //if the user levels up
            var rankUpMessage = rankSettings.xpUpMessage

            // if the user reached a special level, we need to add the special message to the rankUpMessage.
            // we only want to send a roleless message if there is no message for a "role" one.
            ranksXPRankRolesCollection.find(
                and(
                    eq(XPRankRoles::guildId.name, guildId),
                    eq(XPRankRoles::rankLevel.name, newCalc.level.toInt())
                )
            ).firstOrNull()?.let {
                rankUpMessage += "\n${it.specialMessage}"
                event.guild.addRoleToMember(event.member!!, event.guild.getRoleById(it.id)!!).queue()
            } ?: ranksXPRankRolelessCollection.find(
                and(
                    eq(XPRankRoleless::guildId.name, guildId),
                    eq(XPRankRoleless::rankLevel.name, newCalc.level.toInt())
                )
            ).firstOrNull()?.let {
                rankUpMessage += "\n${it.specialMessage}"
            }
            rankUpMessage = messageReplacement(rankUpMessage, newCalc.level.toString(), event)
            event.channel.sendMessage(rankUpMessage).queue()
        }
    }

    // I need to make this use dependency injection. This is because i have a situation where i dont want this enabled.

    suspend fun onMemberJoin(event: GuildMemberJoinEvent) {
        val guildId = event.guild.idLong
        if (!memberJoin) return // meant to exclude guilds using custom implementations.
        val member = event.member
        val guild = event.guild
        val memberId = event.member.idLong

        //todo: This memberJoin should give back the ranks to someone that left the server and rejoined.
        // however, this is unwated in some cases. For example, when the guild has some form of manual
        // verification process. In this case, the user should not get their ranks back until they are verified.
        // maybe that process can include some form of bypass after a set amount of time has passed..
        // for that reason, this isnt recommended.

        ranksCollection.find(
            and(
                eq(RankSettings::status.name, true),
                eq(RankSettings::guildId.name, guildId)
            )
        ).firstOrNull() ?: return //if there are no settings, return
        val exp = getMemberExp(memberId, guildId)
        if ((exp == 0L)) {
            // They're not a returning member.
            //TODO: This should lead to the "Welcome" functionality.
            return
        } else {
            // They're a returning member. They should get their ranks back.
            giveRankRoles(member, guild)
        }
    }

    suspend fun routing(event: MessageReceivedEvent) {
        if (event.message.contentDisplay.substringAfter(" ").equals("!rank", true)) {
            levelCheckMessageVersion(event)
            return
        }
        if (event.message.mentions.members.isNotEmpty()) {
            levelCheckMessageVersion(event, event.message.mentions.members.first())
            return
        }
        try {
            event.message.contentDisplay.split(" ")[1].let {
                val member: Member? = try {
                    event.guild.getMemberById(it)
                } catch (e: Throwable) {
                    try {
                        event.guild.getMembersByName(it, true).firstOrNull()
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (member == null) {
                    event.message.replyEmbeds(
                        NeoSuperEmbed {
                            type = SuperEmbed.ResultType.SIMPLE_ERROR
                            text = "Couldn't find a member with the id or username `$it`"
                        }
                    ).queue()
                    return
                }
                levelCheckMessageVersion(event, member)
            }
        } catch (e: IndexOutOfBoundsException) {
            return
        }
    }

    suspend fun giveRankRoles(member: Member, guild: Guild) {
        val exp = getMemberExp(member.idLong, guild.idLong)
        val level = CalculateLevel(exp).level

        if (exp > 0L) {
            val xpRoles: MutableList<XPRankRoles> = ranksXPRankRolesCollection.find(
                and(
                    eq(XPRankRoles::guildId.name, guild.idLong),
                    lte(XPRankRoles::rankLevel.name, level.toInt())
                )
            ).toList().toMutableList()
            //create another list with only the xproles IDs
            roleCheck(
                commandKlass = Ranks::class,
                roleIds = xpRoles.map { it.id },
                guild = guild,
                functionality = "Ranks XP Roles"
            ).onInvalid {
                it.message += "I've removed the invalid roles from the database."
                ranksXPRankRolesCollection.deleteMany(
                    and(
                        eq(XPRankRoles::guildId.name, guild.idLong),
                        `in`(XPRankRoles::id.name, it.invalidList as List<*>)
                    )
                )
                //delete invalidroles from xpRoles
                xpRoles.removeAll { xprole -> xprole.id in it.invalidList }
            }
            xpRoles.forEach {
                guild.addRoleToMember(member, guild.getRoleById(it.id)!!).queue()
            }
        }
    }
}
