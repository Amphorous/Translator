//package org.hoyo.translator;
//
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.stereotype.Component;
//import org.springframework.beans.factory.annotation.Value;
//
//@Component
//public class RedisConfigCheck implements CommandLineRunner {
//
//    // This grabs the value straight from the active environment properties
//    @Value("${spring.data.redis.host:localhost}")
//    private String redisHost;
//
//    @Value("${spring.data.redis.port:6379}")
//    private String redisPort;
//
//    @Override
//    public void run(String... args) throws Exception {
//        System.out.println("=========================================");
//        System.out.println("TRANSLATOR SERVICE REDIS CONFIGURATION:");
//        System.out.println("Connecting to Redis Host: " + redisHost);
//        System.out.println("Connecting to Redis Port: " + redisPort);
//        System.out.println("=========================================");
//    }
//}