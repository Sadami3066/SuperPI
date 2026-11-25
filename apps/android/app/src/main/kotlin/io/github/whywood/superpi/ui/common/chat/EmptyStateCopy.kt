package io.github.whywood.superpi.ui.common.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.github.whywood.superpi.R
import java.time.LocalTime
import kotlin.random.Random

internal const val EMPTY_STARTER_COUNT = 3

internal data class EmptyCopy(
    val greeting: String,
    val starters: List<String>,
)

internal enum class GreetingTimeBand {
    LATE_NIGHT,
    MORNING,
    AFTERNOON,
    SUNDOWN,
    NIGHT,
}

internal fun greetingTimeBand(hour: Int): GreetingTimeBand = when (hour.coerceIn(0, 23)) {
    in 0..4 -> GreetingTimeBand.LATE_NIGHT
    in 5..10 -> GreetingTimeBand.MORNING
    in 11..16 -> GreetingTimeBand.AFTERNOON
    in 17..19 -> GreetingTimeBand.SUNDOWN
    else -> GreetingTimeBand.NIGHT
}

internal fun selectNormalGreeting(
    general: List<String>,
    morning: String,
    afternoon: String,
    sundown: String,
    night: List<String>,
    lateNight: List<String>,
    hour: Int,
    random: Random,
): String {
    val seasonal = when (greetingTimeBand(hour)) {
        GreetingTimeBand.MORNING -> listOf(morning)
        GreetingTimeBand.AFTERNOON -> listOf(afternoon)
        GreetingTimeBand.SUNDOWN -> listOf(sundown)
        GreetingTimeBand.NIGHT -> night
        GreetingTimeBand.LATE_NIGHT -> lateNight
    }
    val pool = general + seasonal
    if (pool.isEmpty()) return ""
    return pool[random.nextInt(pool.size)]
}

internal fun selectSuperGreeting(
    pool: List<String>,
    random: Random,
): String {
    if (pool.isEmpty()) return ""
    return pool[random.nextInt(pool.size)]
}

internal fun formatGreetingTemplate(template: String, nickname: String): String {
    val cleanName = nickname.trim()
    return if (cleanName.isEmpty()) template else template.replace("{name}", cleanName)
}

internal fun pickRandomItems(
    pool: List<String>,
    count: Int,
    random: Random,
): List<String> {
    if (pool.isEmpty() || count <= 0) return emptyList()
    return pool.shuffled(random).take(count)
}

@Composable
internal fun rememberNormalEmptyCopy(
    sessionKey: Any?,
    nickname: String,
): EmptyCopy {
    val hasName = nickname.trim().isNotEmpty()
    val general = stringArrayResource(
        if (hasName) R.array.assistant_greetings_general_named else R.array.assistant_greetings_general_plain,
    ).toList()
    val morning = stringResource(
        if (hasName) R.string.assistant_greeting_morning_named else R.string.assistant_greeting_morning_plain,
    )
    val afternoon = stringResource(R.string.assistant_greeting_afternoon)
    val sundown = stringResource(R.string.assistant_greeting_evening)
    val night1 = stringResource(R.string.assistant_greeting_night_1)
    val night2 = stringResource(
        if (hasName) R.string.assistant_greeting_night_2_named else R.string.assistant_greeting_night_2_plain,
    )
    val lateNight1 = stringResource(
        if (hasName) R.string.assistant_greeting_late_night_1_named else R.string.assistant_greeting_late_night_1_plain,
    )
    val lateNight2 = stringResource(R.string.assistant_greeting_late_night_2)
    val startersPool = stringArrayResource(R.array.assistant_starter_prompts).toList()
    val seed = remember(sessionKey) { Random.nextLong() }
    val hour = remember(sessionKey) { LocalTime.now().hour }
    return remember(
        seed,
        hour,
        nickname,
        general,
        morning,
        afternoon,
        sundown,
        night1,
        night2,
        lateNight1,
        lateNight2,
        startersPool,
    ) {
        val random = Random(seed)
        val template = selectNormalGreeting(
            general = general,
            morning = morning,
            afternoon = afternoon,
            sundown = sundown,
            night = listOf(night1, night2),
            lateNight = listOf(lateNight1, lateNight2),
            hour = hour,
            random = random,
        )
        EmptyCopy(
            greeting = formatGreetingTemplate(template, nickname),
            starters = pickRandomItems(startersPool, EMPTY_STARTER_COUNT, random),
        )
    }
}

@Composable
internal fun rememberSuperEmptyCopy(
    sessionKey: Any?,
    nickname: String,
): EmptyCopy {
    val hasName = nickname.trim().isNotEmpty()
    val pool = stringArrayResource(
        if (hasName) R.array.super_chat_empty_greetings_named else R.array.super_chat_empty_greetings_plain,
    ).toList()
    val startersPool = stringArrayResource(R.array.super_starter_prompts).toList()
    val seed = remember(sessionKey) { Random.nextLong() }
    return remember(seed, nickname, pool, startersPool) {
        val random = Random(seed)
        val template = selectSuperGreeting(pool, random)
        EmptyCopy(
            greeting = formatGreetingTemplate(template, nickname),
            starters = pickRandomItems(startersPool, EMPTY_STARTER_COUNT, random),
        )
    }
}
