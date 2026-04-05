package org.hoyo.translator.hsr.Controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@CrossOrigin
@RestController
@RequestMapping("/hsr")
public class HSRController {

    private final RedisTemplate redisTemplate;

    public HSRController(@Qualifier("redisTemplate") RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/{key}")
    public String test1(@PathVariable String key){
        return (String) redisTemplate.opsForValue().get(key);

    }
    @GetMapping("/test")
    public String test(){
        return "Hello";

    }
}
