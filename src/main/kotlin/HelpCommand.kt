import net.dv8tion.jda.api.events.message.MessageReceivedEvent

fun scbothelp(event: MessageReceivedEvent) {
        //reply with feature not implemented
        event.message.reply(FEATURE_NOT_IMPLEMENTED).queue()
    }
