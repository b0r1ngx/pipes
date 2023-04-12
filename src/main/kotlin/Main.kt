import java.lang.System.currentTimeMillis
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

val PATTERN = Regex("""\d+""")

const val PIPE_MIN_VALUE = 1
const val PIPE_MAX_VALUE = 10
const val SIT_DELAY = 215
const val BAD_DELAY = 835
const val PIPE_MEAN_DELAY = 1550
const val TIME_TO_DETERMINE_PIPE_VALUE = 3 * PIPE_MEAN_DELAY
const val TIME_TO_THINK_BETTER_PIPE_EXISTS = 2 * PIPE_MEAN_DELAY
const val TIME_TO_WAIT_BETWEEN_SCANS = 4 * PIPE_MEAN_DELAY
const val TIME_TO_WAIT_BETWEEN_CHANGE_TENDENCY = 8 * PIPE_MEAN_DELAY
const val WARMUP_TIME = 12 * PIPE_MEAN_DELAY

enum class Method { GET, PUT, POST }

enum class Direction { Up, Down, Unknown }

enum class Modifiers(val cost: Int) {
    reverse(40), double(50), slow(40), shuffle(10), min(10)
}

fun main(args: Array<String>) {
    val host = args[0]
    val apiUrl = "http://$host/api/pipe"
    val token = args[1]

    val robot = Robot(apiUrl = apiUrl, token = token)

    robot.collectInfoAboutPipes()
    robot.locallyFindBestPipe()

    while (true) {
        robot.ifBadPipeModifyBaddest()
        robot.bestPipe.collect()
        val currentBestPipe = robot.bestPipe
        robot.scanForPipesIfNeeded()
        robot.sitOnPositiveTendencyIfNeeded(currentBestPipe)
    }
}

class Robot(apiUrl: String = "", token: String = "") {
    var gameTime: Int = 0
    var resources: Int = 0
    lateinit var bestPipe: Pipe
    lateinit var lastTouchedPipe: Pipe

    private var lastScanTime: Int = WARMUP_TIME
    private var lastTimeChangeTendency: Int = WARMUP_TIME

    private val pipes = listOf(
        Pipe(robot = this, number = 1, apiUrl = apiUrl, token = token),
        Pipe(robot = this, number = 2, apiUrl = apiUrl, token = token),
        Pipe(robot = this, number = 3, apiUrl = apiUrl, token = token)
    )

    fun locallyFindBestPipe() {
        var bestPipeValue = 0

        pipes.forEach {
            it.recalculateOutputValue()

            var localPipeValue = 0
            var localValue = it.value
            val peoplesOnPipe = if (it == lastTouchedPipe) it.peoplesOnPipe else it.peoplesOnPipe - 1

            when (it.direction) {
                Direction.Down -> for (i in 0 until TIME_TO_DETERMINE_PIPE_VALUE step it.delay) {
                    for (j in 0 until peoplesOnPipe) {
                        localValue--
                        if (localValue < 1) localValue = PIPE_MAX_VALUE
                    }
                    localPipeValue += localValue
                }

                else -> for (i in 0 until TIME_TO_DETERMINE_PIPE_VALUE step it.delay) {
                    for (j in 0 until peoplesOnPipe) {
                        localValue++
                        if (localValue > 10) localValue = PIPE_MIN_VALUE
                    }
                    localPipeValue += localValue
                }
            }
            if (localPipeValue > bestPipeValue) {
                bestPipeValue = localPipeValue
                bestPipe = it
            }
        }
    }

    fun collectInfoAboutPipes() = pipes.forEach {
        it.collectAndSkipOrValueOrCollect()
        if (it.delay <= SIT_DELAY) return
    }

    // todo: not lose points on it, stay tune and just collect and observe by findPipeWithPeoples(),
    // but with this we have a chance to fastest roll new good pipe
    fun ifBadPipeModifyBaddest() {
        if (bestPipe.delay > BAD_DELAY && resources >= 10)
            with(pipes.maxBy { it.delay }) {
                modifier(type = Modifiers.shuffle)
                collectAndSkipOrValueOrCollect()
                locallyFindBestPipe()
            }
    }

    fun scanForPipesIfNeeded() {
        if (bestPipe.delay >= SIT_DELAY + (gameTime / TIME_TO_THINK_BETTER_PIPE_EXISTS)
            && (gameTime - bestPipe.timeAllPeopleOnPipe) >= TIME_TO_THINK_BETTER_PIPE_EXISTS
            && (gameTime - lastScanTime) >= TIME_TO_WAIT_BETWEEN_SCANS
        ) {
            scan(exclude = bestPipe)
            lastScanTime = gameTime
            locallyFindBestPipe()
        }
    }

    // Worst: (200 + some exclude.collect() + 200) &/| (200 + some exclude.collect() + 200 + pipeWhereValueChanged.delay)
    // is that better? (200 + 200 + some excludePipe.collect() + 200 + 200 + pipeWhereValueChanged.delay) =
    // for example, exclude pipe delay = 300, pipeWhereValueChanged 1.55?
    // Time (worst): 200 + 600 + 200 + 200 + 600 + 200 + ((100, 300) -> 200) = 2200
    private fun scan(exclude: Pipe) = getPipes(exclude = exclude).forEach {
        val firstPingValue = it.pingWithValue()
        for (i in 0 until BAD_DELAY step exclude.delay) exclude.collect()
        val secondPingValue = it.pingWithValue()
        if (firstPingValue != secondPingValue) it.collect()
        if (it.delay < exclude.delay) return
    }

    private fun getPipes(exclude: Pipe) =
        pipes.toMutableList().apply {
            removeAt(exclude.number - 1)
            shuffle()
        }

    fun sitOnPositiveTendencyIfNeeded(pipe: Pipe) {
        if (pipe == bestPipe
            && (gameTime - lastTimeChangeTendency) >= TIME_TO_DETERMINE_PIPE_VALUE
        ) {
            sitOnPositiveTendency()
            lastTimeChangeTendency = gameTime
        }
    }

    private fun sitOnPositiveTendency() {
        if (bestPipe.peoplesOnPipe % 2 == 0 && bestPipe.value % 2 == 1) {
            val delay = (bestPipe.delay + 1) / 4
            println("Postpone for $delay")
            postpone(delay = delay)
        }
    }

    private fun postpone(delay: Int) = Thread.sleep(delay.toLong())
}

data class Pipe(
    val robot: Robot,
    val number: Int = 0,

    var value: Int = 0,
    var delay: Int = 3000,
    var direction: Direction = Direction.Unknown,

    var timeOfCollectedValue: Int = 0,
    var peoplesOnPipe: Int = 1,
    var timeAllPeopleOnPipe: Int = WARMUP_TIME,

    val apiUrl: String = "",
    val token: String = ""
) {
    fun collectAndSkipOrValueOrCollect() {
        collect()
        if (delay >= BAD_DELAY) return

        recalculateOutputValue()
        when {
            delay <= SIT_DELAY || (TIME_TO_DETERMINE_PIPE_VALUE / delay) * value >= 69 -> collect()
            else -> value()
        }
    }

    fun recalculateOutputValue() {
        val valueTickTimes = (robot.gameTime - timeOfCollectedValue) / delay
        if (valueTickTimes == 0) return

        timeOfCollectedValue += delay * valueTickTimes

        var nextValue = value
        val peoplesOnPipe = if (this == robot.lastTouchedPipe) this.peoplesOnPipe else this.peoplesOnPipe - 1
        when (direction) {
            Direction.Down -> for (tick in 0 until valueTickTimes) {
                for (j in 0 until peoplesOnPipe) {
                    nextValue--
                    if (nextValue < 1) nextValue = PIPE_MAX_VALUE
                }
            }

            else -> for (tick in 0 until valueTickTimes) {
                for (j in 0 until peoplesOnPipe) {
                    nextValue++
                    if (nextValue > 10) nextValue = PIPE_MIN_VALUE
                }
            }
        }
        value = nextValue
    }

    fun collect() = sendRequest(
        method = Method.PUT,
        url = "$apiUrl/${this.number}",
    )

    fun value() = sendRequest(
        method = Method.GET,
        url = "$apiUrl/${this.number}/value",
    )

    fun modifier(type: Modifiers) = sendRequest(
        method = Method.POST,
        url = "$apiUrl/${this.number}/modifier",
        type = type
    )

    // todo: mean response delay difference from real pipe delay is 5.5 ms
    private fun sendRequest(
        method: Method,
        url: String,
        type: Modifiers? = null
    ) {
        val startTime = currentTimeMillis()
        with(URL(url).openConnection() as HttpURLConnection) {
            requestMethod = method.name
            setRequestProperty("Authorization", "Bearer $token")
            doOutput = true

            if (type != null) {
                setRequestProperty("Content-Type", "application/json")
                outputStream.bufferedWriter().use { it.write("""{"type":"${type.name}"}""") }
            }

            if (method == Method.POST) when (responseCode) {
                200 -> {
                    robot.resources -= type!!.cost
                    println("apply modifier $type to pipe with url: $url")
                }

                422 -> println("failed to apply modifier")
                else -> println("$requestMethod: unexpected status code $responseCode")
            }
            else {
                val newValue: Int
                when (responseCode) {
                    200 -> inputStream.bufferedReader().use {
                        newValue = PATTERN.find(it.readText())?.value?.toInt() ?: 0
                    }

                    else -> {
                        println("$requestMethod: unexpected status code $responseCode")
                        return
                    }
                }

                if (value != 0) {
                    if (abs(newValue - value) <= 4)
                        direction = if (newValue > value) Direction.Up else Direction.Down

                    peoplesOnPipe = when (direction) {
                        Direction.Down -> when (val peoplesOnPipe = value - newValue) {
                            -9 -> 1
                            -8 -> 2
                            -7 -> 3
                            -6 -> 4
                            else -> peoplesOnPipe
                        }

                        else -> when (val peoplesOnPipe = newValue - value) {
                            -9 -> 1
                            -8 -> 2
                            -7 -> 3
                            -6 -> 4
                            else -> peoplesOnPipe
                        }
                    }

                    if (peoplesOnPipe > 3) timeAllPeopleOnPipe = robot.gameTime
                }

                value = newValue
                robot.lastTouchedPipe = this@Pipe

                val responseTime = (currentTimeMillis() - startTime).toInt()
                robot.gameTime += responseTime

                if (method == Method.PUT) {
                    robot.resources += value
                    delay = responseTime
                    timeOfCollectedValue = robot.gameTime
                }

                println(
                    "Time: ${robot.gameTime}. Pipe: $number, peoples: $peoplesOnPipe. " +
                            "Collected $value in $delay (total: ${robot.resources})"
                )
            }
        }
    }

    // 200 MS
    fun pingWithValue(): Int {
        value()
        return value
    }
}
