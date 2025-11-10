package commands

import dev.minn.jda.ktx.coroutines.await
import helpers.*
import kotlinx.coroutines.launch
import myScope
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.HierarchyException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import java.util.concurrent.TimeUnit

fun dontTargetYourself(message: Message) {
    message.reply("No way! I can't believe this!").mentionRepliedUser(false).queue()
}
// kotlin.Result and kotlin.text.Regex will be used with fully qualified names

fun queueSuccess(message: Message, toText: String) {
    message.replyEmbeds(
        NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = toText
        }
    ).mentionRepliedUser(false).queue()
}

fun queueFailure(message: Message, toText: String): (Throwable) -> Unit {
    return {
        message.replyEmbeds(
            throwEmbed(it, toText)
        ).mentionRepliedUser(false).queue()
    }
}

suspend fun kick(event: MessageReceivedEvent) {
    // 1. Permission check
    if (!permissionCheck(event.toWrapper(), Permission.KICK_MEMBERS)) {
        return
    }

    val content = event.message.contentRaw
    val kickParams = parseKickCommand(content)

    if (kickParams.userIds.isEmpty()) {
        event.message.replyEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Usage: !kick <@User1> <@User2> [reason]\nAlso accepts UserIDs\nPlease mention users or provide valid user IDs."
            }
        ).mentionRepliedUser(false).queue()
        return
    }

    for (userId in kickParams.userIds) {
        try {
            val targetUser = event.jda.retrieveUserById(userId).await()
            processKick(event, targetUser, kickParams.reason)
        } catch (e: Exception) {
            queueFailure(event.message, "Could not find user with ID: $userId")(e)
        }
    }
}

private fun processKick(event: MessageReceivedEvent, targetUser: User, reason: String?) {
    // Check if moderator is trying to target themselves
    if (targetUser.id == event.author.id) {
        dontTargetYourself(event.message)
        return
    }

    myScope.launch {
        val result = kickUser(
            guild = event.guild,
            targetUser = targetUser,
            moderator = event.member!!, // moderator is checked non-null in kick()
            reason = reason
        )

        result.onSuccess {
            queueSuccess(event.message, "Successfully kicked ${targetUser.name}.")
        }.onFailure { error: Throwable ->
            val errorText = when (error) {
                is InsufficientPermissionException -> "I don't have the required permissions (${error.permission.name}) to kick members."
                is HierarchyException -> error.localizedMessage // Use message from exception
                is IllegalStateException -> if (error.message?.startsWith("User not in guild") == true) {
                    "${targetUser.name} is not a member of this server."
                } else {
                    "An unexpected state occurred: ${error.localizedMessage}"
                }
                else -> "An unexpected error occurred while trying to kick the user: ${error.localizedMessage}"
            }
            if (errorText.isNotEmpty()) { // Assuming String.isNotEmpty is available
                queueFailure(event.message, errorText)(error)
            }
        }
    }
}

// Helper function to check common moderation prerequisites
private suspend fun checkModerationActionPrerequisites(
    guild: Guild,
    moderator: Member, // The moderator performing the action
    targetUser: User,  // The user being targeted
    actionPermission: Permission, // The specific permission for the action (e.g., KICK_MEMBERS)
    actionRequiresUserInGuild: Boolean // True if the action (like kick) requires the user to be a member
): Result<Member?> { // Returns targetMember if found, or null if not found but action can proceed (e.g. ban)

    // 1. Bot's permission to perform the action
    if (!guild.selfMember.hasPermission(actionPermission)) {
        return Result.failure(InsufficientPermissionException(guild, actionPermission))
    }

    // 2. Retrieve targetMember
    val targetMember: Member? = try {
        guild.retrieveMember(targetUser).await()
    } catch (_: Exception) { // Parameter e is not used
        null // User might not be in the guild
    }

    // 3. Check if user must be in guild for this action
    if (actionRequiresUserInGuild && targetMember == null) {
        return Result.failure(IllegalStateException("User not in guild. This action requires the user to be a member."))
    }

    // 4. Hierarchy checks (only if targetMember exists in the guild)
    if (targetMember != null) {
        val actionVerb = actionPermission.name.substringBefore("_MEMBERS").lowercase() // "kick", "ban"

        if (!moderator.canInteract(targetMember)) {
            return Result.failure(HierarchyException("You cannot $actionVerb this user due to role hierarchy."))
        }
        if (!guild.selfMember.canInteract(targetMember)) {
            return Result.failure(HierarchyException("I cannot $actionVerb this user due to role hierarchy."))
        }
    }

    return Result.success(targetMember) // Success, returning member if found (could be null for ban-like actions on non-members)
}

suspend fun kickUser(
    guild: Guild,
    targetUser: User,
    moderator: Member, // Assumed to have KICK_MEMBERS permission by caller
    reason: String?
): Result<Unit> {
    val prerequisiteResult = checkModerationActionPrerequisites(
        guild = guild,
        moderator = moderator,
        targetUser = targetUser,
        actionPermission = Permission.KICK_MEMBERS,
        actionRequiresUserInGuild = true
    )

    val targetMember = prerequisiteResult.getOrElse { return Result.failure(it) }
    // If actionRequiresUserInGuild = true, a successful result means targetMember is non-null.

    // Send DM to user before kicking (only if all checks pass)
    sendModerationDM(targetUser, "kicked", guild, reason)

    return try {
        guild.kick(targetMember!!) // Assert non-null as kick requires member
            .reason(reason ?: "No reason provided by ${moderator.user.name}.")
            .await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// Refactored ban function to accept parameters
suspend fun banUser(
    guild: Guild,
    targetUser: User,
    moderator: Member, // Assumed to have BAN_MEMBERS permission by caller
    reason: String?,
    daysToDelete: Int,
    replyChannel: MessageChannel // Channel to send feedback to
): Result<Unit> { // Ensure kotlin.Result is used
    val prerequisiteResult = checkModerationActionPrerequisites(
        guild = guild,
        moderator = moderator,
        targetUser = targetUser,
        actionPermission = Permission.BAN_MEMBERS,
        actionRequiresUserInGuild = false // Ban can occur even if user not in guild
    )

    // If prerequisite checks fail (e.g., bot lacks BAN_MEMBERS perm, or hierarchy issue if user IS in guild), return failure.
    prerequisiteResult.exceptionOrNull()?.let { return Result.failure(it) }

    // Check if already banned (this logic is specific to ban and remains)
    try {
        guild.retrieveBan(targetUser).await()
        // If successful, user is already banned
        replyChannel.sendMessageEmbeds( // Using replyChannel directly here
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR // Corrected to ERROR
                text = "${targetUser.name} is already banned."
            }
        ).mentionRepliedUser(false).queue()
        return Result.failure(IllegalStateException("User already banned")) // Indicate failure
    } catch (e: ErrorResponseException) {
        if (e.errorCode == 10026) { // Error code for "Unknown Ban"
            // Expected if user is not banned, continue
        } else {
            // Different error occurred during ban check
            return Result.failure(e)
        }
    } catch (e: Exception) {
        // Other unexpected error during ban check
         return Result.failure(e)
    }

    // Send DM to user before banning (only if all checks pass)
    sendModerationDM(targetUser, "banned", guild, reason)

    // Perform the ban
    return try {
        guild.ban(targetUser, daysToDelete, TimeUnit.DAYS)
            .reason(reason ?: "No reason provided by ${moderator.user.name}.") // Added moderator context to reason
            .await()
        Result.success(Unit)
    } catch (_: Exception) { // Parameter e is not used
        Result.failure(IllegalStateException("Ban failed")) // Return failure if ban fails
    }
}

// Original ban function - now acts as a parser for the !ban command
suspend fun ban(event: MessageReceivedEvent) {
    // Check permissions first
    if (!permissionCheck(event.toWrapper(), Permission.BAN_MEMBERS)) {
        return
    }

    val content = event.message.contentRaw

    // Parse the ban command to extract user IDs, days, and reason
    val banParams = parseBanCommand(content)

    // If no user IDs found, show error
    if (banParams.userIds.isEmpty()) {
        event.message.replyEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Usage: !ban <@User1> <@User2> [days] [reason]\nAlso accepts UserIDs\nDays parameter is optional (0-7 days to delete messages)\nPlease mention users or provide valid user IDs."
            }
        ).mentionRepliedUser(false).queue()
        return
    }

    // Process each user ID found
    for (userId in banParams.userIds) {
        try {
            val targetUser = event.jda.retrieveUserById(userId).await()
            processBan(event, targetUser, banParams.daysToDelete, banParams.reason)
        } catch (e: Exception) {
            queueFailure(event.message, "Could not find user with ID: $userId")(e)
        }
    }
}

// Helper function to continue after user is identified
private fun processBan(event: MessageReceivedEvent, targetUser: User, daysToDelete: Int, reason: String?) {
    // Check if moderator is trying to target themselves
    if (targetUser.id == event.author.id) {
        dontTargetYourself(event.message)
        return
    }

    myScope.launch {
        val result = banUser(
            guild = event.guild,
            targetUser = targetUser,
            moderator = event.member!!,
            reason = reason,
            daysToDelete = daysToDelete,
            replyChannel = event.channel
        )

        result.onSuccess {
            val dayText = if (daysToDelete > 0) " (deleted $daysToDelete day${if (daysToDelete == 1) "" else "s"} of messages)" else ""
            queueSuccess(event.message, "Successfully banned ${targetUser.name}$dayText.")
        }.onFailure { error: Throwable ->
             val errorText = when (error) {
                 is InsufficientPermissionException -> "I don't have the required permissions (${error.permission.name}) to ban members."
                 is HierarchyException -> error.localizedMessage
                 is IllegalStateException -> if (error.message == "User already banned") {
                     ""
                 } else {
                     "An unexpected state occurred: ${error.localizedMessage}"
                 }
                 else -> "An unexpected error occurred while trying to ban the user: ${error.localizedMessage}"
             }
             if (errorText.length > 0) {
                queueFailure(event.message, errorText)(error)
             }
        }
    }
}

// Refactored unban function to accept parameters
suspend fun unbanUser(
    guild: Guild,
    targetUser: User, // User object, as ID is used to fetch it
    moderator: Member,
    reason: String?,
    replyChannel: MessageChannel
): Result<Unit> {
    // Permission check for the moderator
    if (!moderator.hasPermission(Permission.BAN_MEMBERS)) { // BAN_MEMBERS is typically needed for unban too
        return Result.failure(InsufficientPermissionException(guild, Permission.BAN_MEMBERS))
    }
    // Permission check for the bot
    if (!guild.selfMember.hasPermission(Permission.BAN_MEMBERS)) {
        return Result.failure(InsufficientPermissionException(guild, Permission.BAN_MEMBERS))
    }

    // Check if the user is actually banned
    try {
        guild.retrieveBan(targetUser).await()
        // If successful, user is banned, proceed to unban
    } catch (e: ErrorResponseException) {
        if (e.errorCode == 10026) { // Error code for "Unknown Ban"
            replyChannel.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR // Corrected to ERROR
                    text = "${targetUser.name} is not banned."
                }
            ).mentionRepliedUser(false).queue()
            return Result.failure(IllegalStateException("User not banned"))
        }
        // Different error occurred during ban check
        return Result.failure(e)
    } catch (_: Exception) { // Parameter e is not used
        // Other unexpected error
        return Result.failure(IllegalStateException("Unexpected error during unban check"))
    }

    // Perform the unban
    return try {
        guild.unban(targetUser)
            .reason(reason ?: "Unbanned by ${moderator.user.name}.")
            .await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

fun unban(event: MessageReceivedEvent) {
    myScope.launch { // Launch a coroutine for suspend function runChecks
        // For unban, the target user might not be a member, so runChecks might need adjustment
        // or we handle user retrieval differently here. runChecks is designed for current members.
        // However, the updated runChecks can retrieve any user by ID.
        if (!permissionCheck(event.toWrapper(), Permission.BAN_MEMBERS)) {
            return@launch
        }

        val userIds = extractUserIds(event.message.contentRaw)
        if (userIds.isEmpty()) {
            event.message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Usage: !unban <@User/ID> [reason]\nPlease provide a user to unban."
                }
            ).mentionRepliedUser(false).queue()
            return@launch
        }

        val targetUser = try {
            event.jda.retrieveUserById(userIds.first()).await()
        } catch (e: Exception) {
            queueFailure(event.message, "Could not find user with ID: ${userIds.first()}")(e)
            return@launch
        }

        // No self-unban check needed as you can't unban yourself if you are banned.
        // No hierarchy check needed for unban in the same way as ban/kick.

        val moderator = event.member!! // Moderator permission is checked by runChecks

        // Extract reason from args
        val args = event.message.contentRaw.split(Regex("\\s+"), 3)
        val reason = if (args.size > 2) args[2] else null

        val unbanOpResult = unbanUser(
            guild = event.guild,
            targetUser = targetUser,
            moderator = moderator,
            reason = reason,
            replyChannel = event.channel
        )

        unbanOpResult.onSuccess {
            queueSuccess(event.message, "Successfully unbanned ${targetUser.name}.")
        }.onFailure { error ->
            val errorText = when (error) {
                is InsufficientPermissionException -> "I don't have the required permissions (${error.permission.name}) to unban members."
                is IllegalStateException -> "" // Message already sent by unbanUser for "not banned"
                else -> "An unexpected error occurred while trying to unban the user: ${error.localizedMessage}"
            }
            if (errorText.isNotEmpty()) {
                queueFailure(event.message, errorText)(error)
            }
        }
    }
}


suspend fun softban(event: MessageReceivedEvent) {
    // 1. Permission check
    if (!permissionCheck(event.toWrapper(), Permission.BAN_MEMBERS)) { // softban requires ban perms
        return
    }

    val content = event.message.contentRaw
    // A softban is like a kick but with message deletion, so parsing can be similar to kick.
    val softbanParams = parseKickCommand(content) // Reusing kick parser is fine.

    if (softbanParams.userIds.isEmpty()) {
        event.message.replyEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Usage: !softban <@User1> <@User2> [reason]\nAlso accepts UserIDs\nPlease mention users or provide valid user IDs."
            }
        ).mentionRepliedUser(false).queue()
        return
    }

    for (userId in softbanParams.userIds) {
        try {
            val targetUser = event.jda.retrieveUserById(userId).await()
            processSoftban(event, targetUser, softbanParams.reason)
        } catch (e: Exception) {
            queueFailure(event.message, "Could not find user with ID: $userId")(e)
        }
    }
}

private fun processSoftban(event: MessageReceivedEvent, targetUser: User, reason: String?) {
    // Check if moderator is trying to target themselves
    if (targetUser.id == event.author.id) {
        dontTargetYourself(event.message)
        return
    }

    myScope.launch {
        val result = softbanUser(
            guild = event.guild,
            targetUser = targetUser,
            moderator = event.member!!,
            reason = reason,
            replyChannel = event.channel
        )

        result.onSuccess {
            queueSuccess(event.message, "Successfully soft-banned ${targetUser.name}.")
        }.onFailure { error: Throwable ->
            val errorText = when (error) {
                is InsufficientPermissionException -> "I don't have the required permissions (${error.permission.name}) to soft-ban members."
                is HierarchyException -> error.localizedMessage
                else -> "An unexpected error occurred while trying to soft-ban the user: ${error.localizedMessage}"
            }
            if (errorText.isNotEmpty()) {
                queueFailure(event.message, errorText)(error)
            }
        }
    }
}

suspend fun softbanUser(
    guild: Guild,
    targetUser: User,
    moderator: Member,
    reason: String?,
    replyChannel: MessageChannel
): Result<Unit> {
    // A softban is essentially a ban with 1 day of message deletion, followed by an immediate unban.
    val banReason = "Softban: ${reason ?: "No reason provided."}"
    val unbanReason = "Softban unban."

    // Ban the user first to delete messages
    val banResult = banUser(
        guild = guild,
        targetUser = targetUser,
        moderator = moderator,
        reason = banReason,
        daysToDelete = 1, // Key part of a softban
        replyChannel = replyChannel
    )

    if (banResult.isFailure) {
        // If ban fails, check if it's because the user is already banned.
        // If they are already banned, we can proceed to unban them as part of the softban.
        val exception = banResult.exceptionOrNull()
        if (exception is IllegalStateException && exception.message == "User already banned") {
            // This is an acceptable failure for a softban, as the goal is to kick and clear messages.
            // If they are already banned, we just need to unban them.
        } else {
            return banResult // Return the original failure
        }
    }

    // Immediately unban the user
    return unbanUser(
        guild = guild,
        targetUser = targetUser,
        moderator = moderator,
        reason = unbanReason,
        replyChannel = replyChannel
    )
}

// Data class to hold parsed kick command parameters
data class KickCommandParams(
    val userIds: List<String>,
    val reason: String? = null
)

/**
 * Parses a kick command to extract user IDs and an optional reason.
 *
 * Expected format: !kick <@User1> <@User2> [reason]
 *
 * @param content The raw message content
 * @return KickCommandParams containing the parsed parameters
 */
private fun parseKickCommand(content: String): KickCommandParams {
    val userIds = extractUserIds(content)

    // To find the reason, we need to know where the user mentions/IDs end.
    val words = content.split(Regex("\\s+"))
    var lastUserIndex = 0
    for (i in 1 until words.size) {
        val word = words[i]
        if (word.matches(Regex("<@!?(\\d{17,19})>")) || word.matches(Regex("\\d{17,19}"))) {
            lastUserIndex = i
        }
    }

    val reason = if (lastUserIndex + 1 < words.size) {
        words.subList(lastUserIndex + 1, words.size).joinToString(" ")
    } else {
        null
    }

    return KickCommandParams(userIds = userIds, reason = reason)
}

// Data class to hold parsed ban command parameters
data class BanCommandParams(
    val userIds: List<String>,
    val daysToDelete: Int = 0,
    val reason: String? = null
)

/**
 * Parses a ban command to extract user IDs, optional days to delete messages, and reason.
 *
 * Expected format: !ban <@User1> <@User2> [days] [reason]
 * Where days is an optional 1-2 digit number that specifies how many days of messages to delete.
 *
 * @param content The raw message content
 * @return BanCommandParams containing the parsed parameters
 */
private fun parseBanCommand(content: String): BanCommandParams {
    val userIds = mutableListOf<String>()
    var daysToDelete = 0
    var reason: String? = null

    // Regex to match Discord mentions: <@userID> or <@!userID>
    val mentionRegex = Regex("<@!?(\\d{17,19})>")
    val mentionMatches = mentionRegex.findAll(content)
    for (match in mentionMatches) {
        userIds.add(match.groupValues[1])
    }

    // Split content into words for further parsing
    val words = content.split(Regex("\\s+"))
    val processedIndices = mutableSetOf<Int>()

    // Mark command word as processed
    processedIndices.add(0)

    // Mark mention positions as processed
    for (match in mentionMatches) {
        val mentionText = match.value
        for (i in words.indices) {
            if (words[i].contains(mentionText)) {
                processedIndices.add(i)
                break
            }
        }
    }

    // Look for standalone user IDs (17-19 digit numbers)
    for (i in 1 until words.size) {
        val word = words[i]
        if (word.matches(Regex("\\d{17,19}")) && !userIds.contains(word)) {
            userIds.add(word)
            processedIndices.add(i)
        }
    }

    // Look for days parameter (1-2 digit number) after user IDs
    for (i in 1 until words.size) {
        val word = words[i]
        if (!processedIndices.contains(i) && word.matches(Regex("\\d{1,2}"))) {
            val days = word.toIntOrNull()
            if (days != null && days in 0..7) { // Discord allows 0-7 days
                daysToDelete = days
                processedIndices.add(i)
                break // Only take the first valid days number
            }
        }
    }

    // Collect remaining words as reason
    val reasonWords = mutableListOf<String>()
    for (i in 1 until words.size) {
        if (!processedIndices.contains(i)) {
            reasonWords.add(words[i])
        }
    }

    if (reasonWords.isNotEmpty()) {
        reason = reasonWords.joinToString(" ")
    }

    return BanCommandParams(
        userIds = userIds.distinct(),
        daysToDelete = daysToDelete,
        reason = reason
    )
}

private fun extractUserIds(content: String): List<String> {
    val userIds = mutableListOf<String>()

    // Regex to match Discord mentions: <@userID> or <@!userID>
    val mentionRegex = Regex("<@!?(\\d{17,19})>")
    val mentionMatches = mentionRegex.findAll(content)
    for (match in mentionMatches) {
        userIds.add(match.groupValues[1])
    }

    // Also look for standalone user IDs (17-19 digit numbers)
    val args = content.split(Regex("\\s+"))
    for (i in 1 until args.size) {
        val arg = args[i]
        // Check if it's a potential user ID (numeric and 17-19 digits long)
        // Skip if it's already captured as a mention
        if (arg.matches(Regex("\\d{17,19}")) && !userIds.contains(arg)) {
            userIds.add(arg)
        }
    }

    return userIds.distinct()
}

/**
 * Attempts to send a private message to a user before a moderation action.
 * This function silently fails if the user has DMs disabled or blocked the bot.
 *
 * @param user The user to send the message to
 * @param action The action being taken ("kicked" or "banned")
 * @param guild The guild where the action is taking place
 * @param reason The reason for the action (optional)
 */
private suspend fun sendModerationDM(
    user: User,
    action: String,
    guild: Guild,
    reason: String?
) {
    try {
        val reasonText = reason?.let { " Reason: $it" } ?: ""
        val message = "You have been $action from **${guild.name}**.${reasonText}"

        user.openPrivateChannel().await()
            .sendMessage(message)
            .await()
    } catch (_: Exception) {
        // Silently fail if user has DMs disabled or blocked the bot
        // This is expected behavior and shouldn't interrupt the moderation action
    }
}
