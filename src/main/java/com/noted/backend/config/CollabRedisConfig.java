package com.noted.backend.config;

import com.noted.backend.websocket.RedisRoomBroadcaster;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Nghe kenh Redis Pub/Sub de nhan message phong soan thao tu instance khac.
 * Chi bat khi app.collab.redis.enabled=true (production, nhieu instance).
 */
@Configuration
@ConditionalOnProperty(name = "app.collab.redis.enabled", havingValue = "true")
public class CollabRedisConfig {

    @Bean
    public RedisMessageListenerContainer collabRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisRoomBroadcaster redisRoomBroadcaster,
            @Value("${app.collab.redis.channel}") String channel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(redisRoomBroadcaster, new ChannelTopic(channel));
        return container;
    }
}
