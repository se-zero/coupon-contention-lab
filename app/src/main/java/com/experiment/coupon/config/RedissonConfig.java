package com.experiment.coupon.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    /**
     * 분산 락 클라이언트 (W3)
     *
     * 풀 크기·명령 타임아웃·재시도는 기본값을 쓴다. 튜닝하면 비교군 간 새 변수가 된다.
     * 기동 시 접속하므로 Redis 가 없으면 전략과 무관하게 기동에 실패한다.
     */
    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(@Value("${spring.data.redis.host}") String host,
                                  @Value("${spring.data.redis.port}") int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
