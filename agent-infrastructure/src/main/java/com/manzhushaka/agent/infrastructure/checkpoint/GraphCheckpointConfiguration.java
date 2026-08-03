package com.manzhushaka.agent.infrastructure.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
public class GraphCheckpointConfiguration {
    @Bean
    @Primary
    BaseCheckpointSaver graphCheckpointSaver(
            RuntimeStore store,
            @Qualifier("redisGraphCheckpointAcceleration") ObjectProvider<BaseCheckpointSaver> acceleration
    ) {
        return new DurableGraphCheckpointSaver(store, acceleration.getIfAvailable());
    }

    @Bean(name = "graphCheckpointRedissonClient", destroyMethod = "shutdown")
    @Profile("graph-checkpoint-redis")
    RedissonClient graphCheckpointRedissonClient(
            @Value("${agent.graph.checkpoint.redis.address:redis://localhost:6379}") String address,
            @Value("${agent.graph.checkpoint.redis.password:}") String password
    ) {
        Config config = new Config();
        var server = config.useSingleServer().setAddress(address);
        if (!password.isBlank()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }

    @Bean(name = "redisGraphCheckpointAcceleration")
    @Profile("graph-checkpoint-redis")
    BaseCheckpointSaver redisGraphCheckpointAcceleration(
            @Qualifier("graphCheckpointRedissonClient") RedissonClient redissonClient
    ) {
        return RedisSaver.builder().redisson(redissonClient).build();
    }
}
