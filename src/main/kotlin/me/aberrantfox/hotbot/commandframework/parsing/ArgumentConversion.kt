package me.aberrantfox.hotbot.commandframework.parsing

import me.aberrantfox.hotbot.dsls.command.CommandArgument
import me.aberrantfox.hotbot.dsls.command.CommandEvent
import me.aberrantfox.hotbot.extensions.jda.getRoleByIdOrName
import me.aberrantfox.hotbot.extensions.stdlib.*
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.ISnowflake
import net.dv8tion.jda.core.entities.TextChannel

const val separatorCharacter = "|"

val snowflakeConversions = mapOf<ArgumentType, Guild.(String) -> ISnowflake?>(
        ArgumentType.User to { x -> jda.retrieveUserById(x).complete() },
        ArgumentType.TextChannel to Guild::getTextChannelById,
        ArgumentType.VoiceChannel to Guild::getVoiceChannelById,
        ArgumentType.Role to Guild::getRoleByIdOrName
)

val snowflakeArgTypes = snowflakeConversions.keys
val consumingArgTypes = listOf(ArgumentType.Sentence, ArgumentType.Splitter)
val multiplePartArgTypes = listOf(ArgumentType.Sentence, ArgumentType.Splitter, ArgumentType.TimeString)

enum class ArgumentType {
    Integer, Double, Word, Choice, Manual,
    Sentence, User, Splitter, URL, TimeString,
    TextChannel, VoiceChannel, Message, Role
}

data class ConversionResult(val results: List<Any?>? = null,
                            val error: String? = null,
                            val consumed: List<String>? = null) {

    fun then(function: (List<Any?>) -> Any): ConversionResult =
            if (hasError()) {
                this
            } else {
                val nextResult = function.invoke(results!!)

                when (nextResult) {
                    is ConversionResult -> nextResult
                    is List<*> -> ConversionResult(nextResult)
                    is Unit -> this
                    else -> throw IllegalArgumentException("Function must return List, Unit or ConversionResult.")
                }
            }

    fun thenIf(condition: Boolean, function: (List<Any?>) -> Any) =
            if (condition) {
                then(function)
            } else {
                this
            }

    fun hasError() = error != null || results == null
}

fun convertArguments(actual: List<String>, expected: List<CommandArgument>, event: CommandEvent): ConversionResult {

    val expectedTypes = expected.map { it.type }

    if (expectedTypes.contains(ArgumentType.Manual)) {
        return ConversionResult(actual)
    }

    return convertMainArgs(actual, expected)
            .thenIf(expectedTypes.any(snowflakeArgTypes::contains)) {
                retrieveSnowflakes(it, expected, event.guild)
            }.then {
                convertOptionalArgs(it, expected, event)
            }.thenIf(expectedTypes.contains(ArgumentType.Message)) {
                retrieveMessageArgs(it, expected)
            } // final and separate message conversion because dependent on text channel arg being converted already
}

fun convertMainArgs(actual: List<String>, expected: List<CommandArgument>): ConversionResult {

    val converted = arrayOfNulls<Any?>(expected.size)

    val remaining = actual.toMutableList()

    while (remaining.isNotEmpty()) {
        val actualArg = remaining.first()

        val nextMatchingIndex = expected.withIndex().indexOfFirst {
            matchesArgType(actualArg, it.value.type) && converted[it.index] == null
        }
        if (nextMatchingIndex == -1) return ConversionResult(null, "Arguments passed do not match expected ones. Try using the help menu command.")

        val result = convertArg(actualArg, expected[nextMatchingIndex].type, remaining)

        val convertedValue =
                if (result is ConversionResult) when {
                    result.hasError() -> return result
                    else -> result.results!!.first()
                } else {
                    result
                }

        converted[nextMatchingIndex] = convertedValue
    }

    val unfilledNonOptionals = converted.filterIndexed { i, arg -> arg == null && !expected[i].optional }

    if (unfilledNonOptionals.isNotEmpty())
        return ConversionResult(null, "You did not fill all of the non-optional arguments.")

    return ConversionResult(converted.toList())
}

fun retrieveSnowflakes(args: List<Any?>, expected: List<CommandArgument>, guild: Guild): ConversionResult {

    val converted =
            args.zip(expected).map { (arg, expectedArg) ->

                val conversionFun = snowflakeConversions[expectedArg.type]

                if (conversionFun == null || arg == null) return@map arg

                val retrieved =
                        try {
                            conversionFun(guild, (arg as String).trimToID())
                        } catch (e: RuntimeException) {
                            null
                        } ?: return ConversionResult(null, "Couldn't retrieve ${expectedArg.type}: $arg.")

                return@map retrieved
            }

    return ConversionResult(converted)
}

fun retrieveMessageArgs(args: List<Any?>, expected: List<CommandArgument>): ConversionResult {

    val channel = args.firstOrNull { it is TextChannel } as TextChannel?
            ?: throw IllegalArgumentException("Message arguments must be used with a TextChannel argument to be converted automatically")

    val converted = args.zip(expected).map { (arg, expectedArg) ->

        if (expectedArg.type != ArgumentType.Message) return@map arg

        val message =
                try {
                    channel.getMessageById(arg as String).complete()
                } catch (e: RuntimeException) {
                    null
                } ?: return ConversionResult(null, "Couldn't retrieve message from given channel.")

        return@map message
    }

    return ConversionResult(converted)
}

@Suppress("UNCHECKED_CAST")
fun convertOptionalArgs(args: List<Any?>, expected: List<CommandArgument>, event: CommandEvent) =
        args.zip(expected).map { (arg, expectedArg) ->
            arg ?: if (expectedArg.defaultValue is Function<*>)
                       (expectedArg.defaultValue as (CommandEvent) -> Any).invoke(event)
                   else
                       expectedArg.defaultValue
        }


private fun matchesArgType(arg: String, type: ArgumentType): Boolean {
    return when (type) {
        ArgumentType.Integer -> arg.isInteger()
        ArgumentType.Double -> arg.isDouble()
        ArgumentType.Choice -> arg.isBooleanValue()
        ArgumentType.URL -> arg.containsURl()
        else -> true
    }
}

private fun convertArg(arg: String, type: ArgumentType, actual: MutableList<String>): Any {
    val converted =
            when (type) {
                ArgumentType.Integer -> arg.toInt()
                ArgumentType.Double -> arg.toDouble()
                ArgumentType.Choice -> arg.toBooleanValue()
                ArgumentType.Sentence -> joinArgs(actual)
                ArgumentType.Splitter -> splitArg(actual)
                ArgumentType.TimeString -> convertTimeString(actual)
                else -> arg
            }

    if (type !in multiplePartArgTypes) {
        actual.remove(arg)
    } else if (type in consumingArgTypes) {
        actual.clear()
    }

    if (converted is ConversionResult) {
        converted.consumed?.map {
            actual.remove(it)
        }
    }

    return converted
}

private fun joinArgs(actual: List<String>) = actual.reduce { a, b -> "$a $b" }

private fun splitArg(actual: List<String>): List<String> {
    val joined = joinArgs(actual)

    if (!(joined.contains(separatorCharacter))) return listOf(joined)

    return joined.split(separatorCharacter).toList()
}

