@file:OptIn(ExperimentalTime::class, ExperimentalMetadataApi::class)

package armada.example

import armada.example.api.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import io.rsocket.kotlin.ExperimentalMetadataApi
import io.rsocket.kotlin.RSocket
import io.rsocket.kotlin.core.RSocketConnector
import io.rsocket.kotlin.core.WellKnownMimeType
import io.rsocket.kotlin.keepalive.KeepAlive
import io.rsocket.kotlin.metadata.CompositeMetadata
import io.rsocket.kotlin.metadata.RoutingMetadata
import io.rsocket.kotlin.metadata.metadata
import io.rsocket.kotlin.metadata.read
import io.rsocket.kotlin.payload.Payload
import io.rsocket.kotlin.payload.PayloadMimeType
import io.rsocket.kotlin.payload.buildPayload
import io.rsocket.kotlin.transport.ktor.client.RSocketSupport
import io.rsocket.kotlin.transport.ktor.client.rSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@ExperimentalSerializationApi
class SmarterTargetingClient {

    private val base = URLBuilder("http://localhost:7000/api/v1")

    private fun Payload.route(): String = metadata?.read(RoutingMetadata)?.tags?.first() ?: error("No route provided")

    private val client = HttpClient(CIO) {
        install(JsonFeature) {
            serializer = JacksonSerializer()
        }
        install(WebSockets)
        install(RSocketSupport) {
            connector = RSocketConnector {
                connectionConfig {
                    keepAlive = KeepAlive(
                        interval = seconds(30),
                        maxLifetime = Duration.minutes(2)
                    )
                    payloadMimeType = PayloadMimeType(
                        metadata = WellKnownMimeType.MessageRSocketCompositeMetadata.text,
                        data = WellKnownMimeType.ApplicationJson.text
                    )
                }
            }
        }
    }

    private val observer = Channel<Coord>()

    private val phase2Trigger = Channel<Boolean>()

    private suspend fun register(name: String): TheatreData {
        return client.post {
            url {
                takeFrom(base).pathComponents("register")
            }
            contentType(ContentType.Application.Json)
            body = RegistrationData(name)
        }
    }

    private suspend fun target(turret: Int, x: Int, y: Int): ActionResultData {
        return client.post {
            url {
                takeFrom(base).pathComponents("target")
            }
            contentType(ContentType.Application.Json)
            body = TargetData(x, y, turret)
        }
    }

    private suspend fun load(turret: Int, gun: Int): ActionResultData {
        return client.post {
            url {
                takeFrom(base).pathComponents("load")
            }
            contentType(ContentType.Application.Json)
            body = GunData(turret, gun)
        }
    }

    private suspend fun fire(turret: Int, gun: Int): ActionResultData {
        return client.post {
            url {
                takeFrom(base).pathComponents("fire")
            }
            contentType(ContentType.Application.Json)
            body = GunData(turret, gun)
        }
    }

    private suspend fun finish(): ScoreData {
        return client.get {
            url {
                takeFrom(base).pathComponents("finish")
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    inner class Gunner(private val turret: Int) {

        val channel = Channel<Coord>(10)

        private val dispatcher: CoroutineDispatcher

        private var counter: Int = 0

        init {
            dispatcher = Executors.newSingleThreadExecutor {
                val thread = Thread(it)
                thread.isDaemon = true
                thread
            }.asCoroutineDispatcher()
        }

        fun start() {
            GlobalScope.launch(dispatcher) {
                while (isActive) {
                    val coord = channel.receive()
                    val gun = counter++ % 2
                    with(coord) {
                        val aimed = async { target(turret, x, y) }
                        val loaded = async { load(turret, gun) }
                        awaitAll(aimed, loaded)
                        fire(turret, gun)
                        observer.send(this@with)
                    }
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun start() = coroutineScope {

        val theatre = register("Ian's Smarter Example")

        val grid = Array(theatre.gridWidth) { Array(theatre.gridHeight) { Tile.UNKNOWN } }

        val equator = theatre.gridHeight / 2

        GlobalScope.launch(Executors.newSingleThreadExecutor {
            val thread = Thread(it)
            thread.isDaemon = true
            thread
        }.asCoroutineDispatcher()) {
            while (isActive) {
                val coord = observer.receive()
                with(coord) {
                    val wait =
                        ((sqrt(((theatre.gridWidth - x) * 100.0).pow(2) + ((equator - y) * 100.0).pow(2)) / 750.0) * 1000).roundToLong()
                    launch {
                        delay(milliseconds(wait))
                        grid[x][y] = Tile.CHECK
                    }
                }
            }
        }

        val gunners = Array(2) { Gunner(it) }

        val firingSequence: suspend (Coord) -> Unit = { c ->
            with(c) {
                val gunner = if (y > equator) {
                    gunners[0]
                } else {
                    gunners[1]
                }
                if (grid[x][y] == Tile.UNKNOWN) {
                    grid[x][y] = Tile.STANDBY
                    gunner.channel.send(c)
                }
            }
        }

        gunners.forEach { it.start() }

        observeSatelliteFeed(grid) { x, y ->
                firingSequence(Coord(x, y))
        }

        for (x in 0 until theatre.gridWidth step 4) {
            for (y in 0 until theatre.gridHeight step 2) {
                val x1 = if ((y / 2) % 2 == 0) x else x + 2
                if (x1 >= theatre.gridWidth) continue
                firingSequence(Coord(x1, y))
            }
        }

        phase2Trigger.receive()

        for (x in 0 until theatre.gridWidth step 4) {
            for (y in 0 until theatre.gridHeight step 2) {
                val x1 = if ((y / 2) % 2 == 1) x else x + 2
                if (x1 >= theatre.gridWidth) continue
                firingSequence(Coord(x1, y))
            }
        }

        phase2Trigger.receive()

        finish()

        client.close()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun observeSatelliteFeed(grid: Array<Array<Tile>>, target: suspend (Int, Int) -> Unit) {
        var count = 0
        var countCoord = Coord(0,0)
        GlobalScope.launch(Executors.newSingleThreadExecutor {
            val thread = Thread(it)
            thread.isDaemon = true
            thread
        }.asCoroutineDispatcher()) {
            val rSocket: RSocket = client.rSocket("ws://localhost:7000/rsocket")
            val stream: Flow<Payload> =
                rSocket.requestStream(buildPayload {
                    data(ByteReadPacket.Empty); metadata(
                    CompositeMetadata(
                        RoutingMetadata(
                            "api.v1.messages.scanner"
                        )
                    )
                )
                })
            stream.takeWhile { isActive }.collect {
                val scan = Json.decodeFromString<ScanData>(it.data.readText())
                with(scan) {
                    if ((x == 0 && y == 0) || (x == grid.size - 1 && y == grid[0].size - 1)) {
                        if (countCoord != Coord(x, y)) {
                            countCoord = Coord(x, y)
                            println("End $x,$y")
                            val current = grid.sumOf { x -> x.count { t -> t == Tile.UNKNOWN } }
                            if (count == current) {
                                phase2Trigger.send(true)
                            } else {
                                count = current
                            }
                        }
                    }
                    if (grid[x][y] == Tile.CHECK) {
                        if (thermalIndex > 0) {
                            grid[x][y] = Tile.HOT
                            println("Hit $x,$y")
                            val testCoords = ArrayList<Coord>()
                            if (x < grid.size - 1) {
                                testCoords.add(Coord(x + 1, y))
                            }
                            if (x > 0) {
                                testCoords.add(Coord(x - 1, y))
                            }
                            if (y < grid[0].size - 1) {
                                testCoords.add(Coord(x, y + 1))
                            }
                            if (y > 0) {
                                testCoords.add(Coord(x, y - 1))
                            }
                            testCoords.forEach { coord ->
                                with(coord) {
                                    if (grid[x][y] == Tile.UNKNOWN) {
                                        println("Shooting: $x, $y")
                                        target(x, y)
                                    }
                                }
                            }
                        } else {
                            grid[x][y] = Tile.COLD
                        }
                    }
                }
            }
        }
    }

    companion object {
        enum class Tile { UNKNOWN, STANDBY, CHECK, COLD, HOT }
        data class Coord(val x: Int, val y: Int)
    }
}

@OptIn(ExperimentalSerializationApi::class)
suspend fun main(args: Array<String>) {
    SmarterTargetingClient().start()
}