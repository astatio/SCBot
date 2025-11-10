import com.mongodb.kotlin.client.coroutine.MongoDatabase
import net.dv8tion.jda.api.JDA
import kotlin.contracts.contract
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind

//todo: SpamPrevention is far from ideal. One of the cases in which it fails is when a user sends an image.
// The user might be spamming images, but the bot won't be able to detect that the image is the same or not - in case its being uploaded each time.

//todo: OnMemberJoin is also far from ideal.


/**
 * An object that holds the requirements for SparksEngine to run properly.
 * This object should be initialized right as the bot starts.
 *
 * @property database The MongoDB database that the bot will use. If its not initialized before the first access, the program may crash.
 * @property jda The JDA object that the bot will use. If its not initialized before the first access, the program may crash.
*/
data class Requirements (
	var database : MongoDatabase? = null,
	var jda : JDA? = null
)

/**
 * An object that holds the optional requirements for SparksEngine to run properly.
 * This object should be initialized right as the bot starts.
 *
 * @property spamPrevention A boolean that will be checked if SpamPrevention should be enabled or not. Its false by default.
 * @property onMemberJoin A boolean that will be checked if MemberJoin implementation from SparksEngine should be enabled or not. Its false by default.
 * @property owners An array of Longs that will be checked if the user is allowed to use the "!eval" command or not. The array starts empty, meaning that no one can use the command.
 */
data class Optionals(
	var spamPrevention: Boolean = false,
	var onMemberJoin: Boolean = false,
	var owners: Array<Long> = arrayOf()
)


@ExperimentalContracts
fun initializeRequirements(init: Requirements.() -> Unit) {
	contract {
		callsInPlace(init, InvocationKind.EXACTLY_ONCE)
	}
	val tmp = Requirements().apply(init)
	database = tmp.database ?: throw IllegalArgumentException("Database is required")
	jda = tmp.jda ?: throw IllegalArgumentException("JDA is required")
}

@ExperimentalContracts
fun initializeOptionals(init: Optionals.() -> Unit) {
	contract {
		callsInPlace(init, InvocationKind.EXACTLY_ONCE)
	}
	val tmp = Optionals().apply(init)
	spamPrevention = tmp.spamPrevention
	memberJoin = tmp.onMemberJoin
	owners = tmp.owners
}



///main function example
//fun main() {
//	initializeRequirements {
//		database = MongoDatabase()
//		jda = JDA()
//	}
//	initializeOptionals {
//		spamPrevention = true
//		onMemberJoin = true
//		owners = arrayOf(1234567890)
//	}
