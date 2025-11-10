package commands.ranks

import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.Sorts.descending
import commands.ranks.Ranks.ranksMemberCollection
import kotlinx.coroutines.flow.toList

object LeaderboardHelper {

    suspend fun getTopMembers(guildId: Long): List<Ranks.RankMember> {
        return ranksMemberCollection.find(
            and(
                eq("guildId", guildId),
                exists("exp")
            )
        ).sort(descending("exp")).limit(100).toList()
    }
}
