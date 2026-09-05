package com.operator.backend

import com.operator.backend.config.BackendConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("operator-backend")
    val config = BackendConfig.fromEnvironment()
    log.info("OPERATOR backend {} starting on {}:{} (prompt {})", BACKEND_VERSION, config.host, config.port, config.promptVersion)
    log.info("Config: {}", config.redacted())
    val deps = BackendDependencies.fromConfig(config)
    Runtime.getRuntime().addShutdownHook(Thread { deps.close() })
    embeddedServer(Netty, port = config.port, host = config.host) { operatorModule(deps) }.start(wait = true)
}
