package com.noted.backend.config;

import com.noted.backend.websocket.NoteCollabWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.List;

/**
 * Endpoint WebSocket cua soan thao cong tac: /ws/notes/{noteId}.
 *
 * Xac thuc KHONG nam o Spring Security (trinh duyet khong gan duoc header
 * Authorization cho WebSocket) - handler tu kiem JWT trong Sec-WebSocket-Protocol,
 * nen SecurityConfig de /ws/** permitAll. Chong ket noi tu trang la (CSWSH) bang
 * kiem tra Origin theo CUNG danh sach CORS cua REST.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final NoteCollabWebSocketHandler noteCollabWebSocketHandler;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Value("${app.collab.idle-timeout-ms}")
    private long idleTimeoutMs;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(noteCollabWebSocketHandler, "/ws/notes/*")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // Buffer NHO: Tomcat cap buffer nay cho MOI ket noi. Message lon hon (trang
        // thai day du cua note dai) duoc chia manh va handler tu ghep, co gioi han
        // rieng app.collab.max-message-bytes.
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxTextMessageBufferSize(8 * 1024);
        // Client gia han awareness moi 15s nen ket noi song khong bao gio cham nguong nay.
        container.setMaxSessionIdleTimeout(idleTimeoutMs);
        return container;
    }
}
