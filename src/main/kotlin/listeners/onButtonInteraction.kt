package listeners

import FEATURE_NOT_IMPLEMENTED
import com.mongodb.client.model.Filters
import commands.Ticketing.assignTicket
import commands.Ticketing.closeTicket
import commands.UserInfo
import core.ModLog
import core.ModLog.pingCollection
import dev.minn.jda.ktx.messages.Embed
import helpers.BookHandler.bookRegex
import helpers.BookHandler.registeredBookRouters
import helpers.permissionCheck
import helpers.ratelimiters.InteractionRateLimiter.rateLimitCheck
import helpers.toWrapper
import interfaces.ButtonInteractions
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import logger
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.modals.Modal
import org.bson.types.ObjectId

object ButtonInteractionsImpl : ButtonInteractions {

    override suspend fun onButtonInteraction(event: ButtonInteractionEvent) {
        // I can't put event.deferEdit() here as the other ephemeral button interactions will also be
        // acknowledged and it might cause issues
        if (event.componentId.startsWith("ping-on-return-")) {
            event.deferEdit().queue() // acknowledge the button was clicked; otherwise the interaction will fail

            val userToReturnId = event.componentId.replace("ping-on-return-", "").toLong()
            val userToPingId = event.user.idLong
            val guildId = event.guild!!.idLong

            // Try to find the ping in MongoDB
            val existingPing = pingCollection.find(
                Filters.and(
                    Filters.eq(ModLog.Ping::userToPing.name, userToPingId),
                    Filters.eq(ModLog.Ping::userToReturn.name, userToReturnId),
                    Filters.eq(ModLog.Ping::guildId.name, guildId)
                )
            ).firstOrNull()

            if (existingPing != null) {
                pingCollection.deleteOne(
                    Filters.and(
                        Filters.eq(ModLog.Ping::userToPing.name, userToPingId),
                        Filters.eq(ModLog.Ping::userToReturn.name, userToReturnId),
                        Filters.eq(ModLog.Ping::guildId.name, guildId)
                    )
                )
                event.hook.setEphemeral(true)
                    .sendMessage("You will no longer be notified when this user returns").queue()
            } else {
                // Insert a new ping into MongoDB
                val newPing = ModLog.Ping(userToPingId, userToReturnId, guildId)
                pingCollection.insertOne(newPing)
                event.hook.setEphemeral(true).sendMessage("You will be notified when this user returns")
                    .queue()
            }
            return
        }
        if (event.componentId.startsWith("modify-reason-")) {
            event.deferEdit().queue() // acknowledge the button was clicked; otherwise the interaction will fail
            event.hook.setEphemeral(true).sendMessage(FEATURE_NOT_IMPLEMENTED).queue()
            return
        }
        if (event.componentId.startsWith("super-userinfo-")) {
            event.deferEdit().queue()
            UserInfo.superUserInfo(event)
            return
        }
        if (event.componentId.startsWith("show-userinfo-")) {
            event.deferReply().queue()
            UserInfo.showSuperUserInfoInChat(event)
            return
        }
        //the below is for the role picker conf buttons. Here will be routing to the corresponding functions.
        if (event.componentId.startsWith("book-")) {
            event.deferEdit().queue()
            ///typical component id: book-<bookName>-<pageName>
            //the book cant contain hyphens, numbers nor special characters it must be purely alphabetical
            val match = bookRegex.find(event.componentId)
            val (book, page) = match?.destructured ?: run {
                logger.error { "Book regex failed to match for componentId: ${event.componentId}" }
                return
            }

            registeredBookRouters.firstOrNull {
                it.supportedBooks.contains(book)
            }?.route(event, book, page)
            return
        }
        // Handle String Select management buttons (Add/Remove role)
        if (event.componentId.startsWith("rp-string-")) {
            if (!permissionCheck(event.toWrapper(), Permission.MANAGE_ROLES)) {
                event.hook.sendMessage("This button was not meant for you.").setEphemeral(true).queue()
                return
            }
            // Expected: rp-string-add-<groupId> or rp-string-remove-<groupId>
            val parts = event.componentId.split("-")
            if (parts.size >= 4) {
                val action = parts[2]
                val groupId = parts[3]
                // Validate ObjectId format
                val valid = runCatching { ObjectId(groupId) }.isSuccess
                if (!valid) {
                    event.deferReply(true).queue()
                    event.hook.sendMessage("Invalid role picker group. Please try again later.").queue()
                    return
                }
                when (action) {
                    "add" -> {
                        val modal = Modal.create("rp-string-add-$groupId", "Add Role")
                            .addComponents(
                                Label.of(
                                    "Enter the Role ID to add",
                                    TextInput.create(
                                        "role_id",
                                        TextInputStyle.SHORT
                                    ).setRequired(true).build()
                                )
                            ).build()
                        event.replyModal(modal).queue()
                    }

                    "remove" -> {
                        val modal = Modal.create("rp-string-remove-$groupId", "Remove Role")
                            .addComponents(
                                Label.of(
                                    "Enter the Role ID to remove",
                                    TextInput.create(
                                        "role_id",
                                        TextInputStyle.SHORT
                                    ).setRequired(true).build()
                                )
                            ).build()
                        event.replyModal(modal).queue()
                    }

                    else -> {
                        event.deferReply(true).queue()
                        event.hook.sendMessage("Unknown action.").queue()
                    }
                }
                return
            }
        }
        if (event.componentId.startsWith("rp-")) {
            //todo: this is the part that is missing for the tests to start
            event.rateLimitCheck()?.let {
                event.reply("You have done this too many times! Try again in $it").setEphemeral(true).queue()
                return
            }

            //Example:
            //`rp-0-1202041954951770112-329c9d9d9d9d329329`
            //Example for modal:
            //`rp-0-m-329c9d9d9d9d329329`

            //i need some regex
            val captureGroups = rpRegex.find(event.componentId)?.groupValues
                ?: run {
                    event.deferReply().queue()
                    event.hook.setEphemeral(true).sendMessage("Seems like this button was not meant to work.").queue()
                    return
                }
            // 0 -> means the whole thing
            // 1 -> the index group (can contain m suffix)
            // 2 -> the role ID
            // 3 -> the optional ObjectID for the group

            val groupIdStr = captureGroups.getOrNull(3)


            event.deferReply(true).queue()
            //after dealing with modals, the only other scenario left is getting the role ID from the interaction ID
            // and give it to the user without any further database calls
            event.guild?.getRoleById(captureGroups[2])?.let { role ->
                if (event.member?.roles?.contains(role) == true) {
                    event.guild!!.removeRoleFromMember(event.member!!, role).queue(
                        {
                            event.hook.sendMessage("You no longer have the role ${role.asMention}")
                                .queue()
                        },
                        {
                            event.hook.sendMessage("Something went wrong. Try again later").queue()
                        }
                    )
                    return
                }
                event.guild!!.addRoleToMember(event.member!!, role).queue(
                    {
                        event.hook.sendMessage("You now have the role ${role.asMention}").queue()
                    },
                    {
                        event.hook.sendMessage("Something went wrong. Try again later").queue()
                    }
                )
            }
            return
        }
        if (event.componentId.startsWith("give-")) {
            event.deferEdit().queue()
            val captureGroups = giveRegex.find(event.componentId)?.groupValues
                ?: run {
                    event.hook.setEphemeral(true).sendMessage("Seems like this button was not meant to work.").queue()
                    return
                }
            // This button is meant to give the role to the user on click.
            // The index 1 capture group is the role ID
            // The index 2 capture group is the user ID
            // check if the user can receive the role
            val targetMember = event.guild?.getMemberById(captureGroups[2])
                ?: run {
                    event.hook.setEphemeral(true).sendMessage("I can't find this member in this server.").queue()
                    return
                }
            val targetRole = event.guild?.getRoleById(captureGroups[1]) ?: run {
                event.hook.setEphemeral(true).sendMessage("I can't find this role in this server.").queue()
                return
            }
            if (targetMember.roles.contains(targetRole)) {
                event.hook.setEphemeral(true).sendMessage("This user already has this role.").queue()
                return
            }

            event.guild?.addRoleToMember(targetMember, targetRole)?.queue(
                {
                    event.hook.setEphemeral(true)
                        .sendMessage("Role ${targetRole.asMention} has been given to ${targetMember.asMention}").queue()
                },
                {
                    event.hook.setEphemeral(true).sendMessage("Something went wrong. Try again later.").queue()
                }
            )

            // the embed needs to be edited in order to show that a mod already answered to that request
            val embed = event.message.embeds[0]
            val newEmbed = Embed {
                embed.thumbnail?.url?.let { thumb ->
                    thumbnail = thumb
                }
                title = embed.title
                description = embed.description
                color = embed.color?.rgb
                timestamp = embed.timestamp
                //copy all fields
                embed.fields.forEach {
                    field {
                        name = it.name ?: ""
                        value = it.value ?: ""
                        inline = it.isInline
                    }
                }
                footer {
                    // get the name and delete the last 8 characters (Pending)
                    name = embed.footer?.text?.dropLast(8).toString() + " ${event.user.name}"
                    iconUrl = event.member?.effectiveAvatarUrl
                }
            }
            event.message.editMessageEmbeds(newEmbed).queue()
        }
        if (event.componentId.startsWith("ticket-")) {
            val componentSplitId = event.componentId.split("-")
            if (!permissionCheck(event.toWrapper(), Permission.MANAGE_ROLES))
                return

            when (componentSplitId.getOrNull(1)) {
                "close" -> {
                    event.deferEdit().queue()
                    val ticketId = componentSplitId.getOrNull(2) ?: return
                    closeTicket(event, ticketId)
                }

                "assign" -> {
                    event.deferEdit().queue()
                    val ticketId = componentSplitId.getOrNull(2) ?: return
                    assignTicket(event, ticketId)
                }

                "assignother" -> {
                    val ticketId = componentSplitId.getOrNull(2) ?: return
                    val selectMenu = EntitySelectMenu.create(
                        "ticket-assignselect-$ticketId",
                        EntitySelectMenu.SelectTarget.USER
                    )
                        .setPlaceholder("Select a user to assign")
                        .setRequiredRange(1, 1)
                        .build()

                    event.replyComponents(ActionRow.of(selectMenu))
                        .setEphemeral(true)
                        .setContent("Choose a new assignee for this ticket:")
                        .queue()
                }
            }
        }
    }
}

val rpRegex = Regex("""^rp-(\d+m?)-([0-9a-zA-Z]+)(?:-([0-9a-zA-Z]+))?$""")
val giveRegex = Regex("""^give-([0-9a-zA-Z]+)-([0-9a-zA-Z]+)$""")
