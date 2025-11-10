package commands.filters

object FilterCommons {
    //The code below was in ModLog.kt
// Some of it might really only be useful for the ModLog command
    val censoredWords = listOf("nigg", "retard", "chink", "kike", "fag", "tard") // swear words
    // This needs to be improved - not be hardcoded
    val censoredPatternRegex = censoredWords.map { Regex(it, RegexOption.IGNORE_CASE) }

    fun censor(string: String): String {
        // What should happen to a message with n-word: n--ga lasagna
        // Return an empty string if msg is empty. Example: empty nicknames
        if (string.isEmpty()) return ""

        var censoredString = string
        censoredPatternRegex.forEach { pattern ->
            censoredString = pattern.replace(censoredString) { match ->
                val word = match.value
                if (word.length <= 2)
                    word
                else
                    word.first() + "-".repeat(word.length - 2) + word.last()

            }
        }
        return censoredString
    }
}