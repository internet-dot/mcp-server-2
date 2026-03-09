package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.LoggerFactory
import java.io.File
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end test verifying the full stack:
 * TestStdioMcpClient (stdio) ↔ proxy subprocess ↔ KtorServerManager (SSE)
 *
 * Requires libs/mcp-proxy-all.jar to be present (built via `./gradlew embedProxyJar` from root).
 */
class ProxyEndToEndTest {
    private val logger = LoggerFactory.getLogger(ProxyEndToEndTest::class.java)

    private val api = mockk<MontoyaApi>(relaxed = true)
    private val serverManager = KtorServerManager(api)
    private val testPort = findAvailablePort()
    private val persistedObject = mockk<PersistedObject>()
    private var serverStarted = false

    init {
        every { persistedObject.getBoolean(any()) } returns true
        every { persistedObject.getString(any()) } returns "127.0.0.1"
        every { persistedObject.getInteger("port") } returns testPort
        every { persistedObject.setBoolean(any(), any()) } returns Unit
        every { persistedObject.setString(any(), any()) } returns Unit
        every { persistedObject.setInteger(any(), any()) } returns Unit
    }

    private val mockLogging = mockk<Logging>().apply {
        every { logToError(any<String>()) } returns Unit
        every { logToOutput(any<String>()) } returns Unit
    }

    private val config = McpConfig(persistedObject, mockLogging)

    private lateinit var proxyProcess: Process
    private lateinit var client: TestStdioMcpClient

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    @BeforeEach
    fun setup(): Unit = runBlocking {
        serverManager.start(config) { state ->
            if (state is ServerState.Running) {
                serverStarted = true
            }
        }

        var attempts = 0
        while (!serverStarted && attempts < 10) {
            delay(100)
            attempts++
        }
        if (!serverStarted) {
            throw IllegalStateException("Server failed to start after timeout")
        }

        val jarFile = File("libs/mcp-proxy-all.jar")
        check(jarFile.exists()) {
            "libs/mcp-proxy-all.jar not found. Build it first: ./gradlew embedProxyJar (from repo root)"
        }

        proxyProcess = ProcessBuilder(
            "java",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
            "-jar",
            jarFile.absolutePath,
            "--sse-url",
            "http://127.0.0.1:$testPort"
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

        delay(3.seconds)

        client = TestStdioMcpClient()
        client.connectToServer(proxyProcess.inputStream, proxyProcess.outputStream)
        logger.info("Test client connected to proxy on port $testPort")
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        try {
            if (::client.isInitialized) {
                client.close()
            }
        } catch (e: Exception) {
            logger.warn("Error closing client: ${e.message}")
        }

        try {
            if (::proxyProcess.isInitialized) {
                proxyProcess.destroy()
                if (proxyProcess.isAlive) {
                    delay(1000)
                    proxyProcess.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            logger.warn("Error destroying proxy process: ${e.message}")
        }

        serverManager.stop {}
    }

    @Test
    fun `proxy should ping server`() {
        runBlocking {
            assertDoesNotThrow { client.ping() }
        }
    }

    @Test
    fun `proxy should list tools`() {
        runBlocking {
            val tools = client.listTools()
            assertFalse(tools.isEmpty(), "Tool list should not be empty")
            assertTrue(tools.any { it.name == "url_encode" }, "url_encode tool should be present")
        }
    }

    @Test
    fun `proxy should call url_encode tool`() {
        runBlocking {
            val result = client.callTool("url_encode", mapOf("content" to "hello world"))
            assertNotNull(result, "Tool call result should not be null")
            assertFalse(result?.isError ?: true, "Tool call should not return an error")
        }
    }
}
