package esw.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler

@SpringBootApplication
class EswApplication

fun main(args: Array<String>) {
    runApplication<EswApplication>(*args)
}

@Component
class GameSocketHandler(private val hub: Hub) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) {
        hub.connected(ConcurrentWebSocketSessionDecorator(session, 10_000, 1024 * 1024))
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        hub.message(session, message.payload)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        hub.closed(session)
    }
}

@Configuration
@EnableWebSocket
class WebSocketConfig(private val handler: GameSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns("*")
    }
}
