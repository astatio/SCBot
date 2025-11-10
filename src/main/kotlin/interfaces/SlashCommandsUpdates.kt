package interfaces
import net.dv8tion.jda.api.JDA

interface SlashCommandsUpdates {
    suspend fun start(jda: JDA)
}