package com.operator.backend

import com.operator.backend.config.BackendConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("operator-backend")
    val config = BackendConfig.fromEnvironment()
    log.info("OPERATOR backend {} starting on {}:{} (prompt {})", BACKEND_VERSION, config.host, config.port, config.promptVersion)
    // Say where configuration came from. Silence here is what made a missing .env look like a
    // missing key: every provider reported "not configured" and nothing explained why.
    BackendConfig.loadedEnvFile
        ?.let { log.info("Loaded .env from {}", it) }
        ?: log.warn("No .env found (searched the working directory and its parents); using real environment variables only")
    log.info("Config: {}", config.redacted())
    val deps = BackendDependencies.fromConfig(config)
    Runtime.getRuntime().addShutdownHook(Thread { deps.close() })
    embeddedServer(Netty, port = config.port, host = config.host) { operatorModule(deps) }.start(wait = true)
}
