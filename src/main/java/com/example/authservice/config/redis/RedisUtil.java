package com.example.authservice.config.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisUtil {
    private final StringRedisTemplate template;
    private final RedisTemplate<String, Object> objectRedisTemplate;

    public RedisUtil(
            StringRedisTemplate template,
            @Qualifier("objectRedisTemplate") RedisTemplate<String, Object> objectRedisTemplate
    ) {
        this.template = template;
        this.objectRedisTemplate = objectRedisTemplate;
    }

    public String getData(String key) {
        ValueOperations<String, String> valueOperations = template.opsForValue();
        return valueOperations.get(key);
    }

    public boolean existData(String key) {
        return Boolean.TRUE.equals(template.hasKey(key));
    }

    public void setDataExpire(String key, String value, long duration) {
        ValueOperations<String, String> valueOperations = template.opsForValue();
        Duration expireDuration = Duration.ofSeconds(duration);
        valueOperations.set(key, value, expireDuration);
        System.out.println("✅ setDataExpire 실행됨 - key: " + key + ", value: " + value + ", duration: " + duration + "s");
    }

    public void deleteData(String key) {
        template.delete(key);
    }

    // 객체 저장
    public void setObjectDataExpire(String key, Object value, long duration) {
        ValueOperations<String, Object> ops = objectRedisTemplate.opsForValue();
        ops.set(key, value, Duration.ofSeconds(duration));
    }

    public Object getObjectData(String key) {
        return objectRedisTemplate.opsForValue().get(key);
    }
}
