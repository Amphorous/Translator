package org.hoyo.translator.debug;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

//@Component
//public class RedisDiagnosticsRunner implements CommandLineRunner {
//
//    @Value("${spring.data.redis.host}")
//    private String redisHost;
//
//    @Value("${spring.data.redis.port}")
//    private int redisPort;
//
//    private final RedisConnectionFactory redisConnectionFactory;
//
//    public RedisDiagnosticsRunner(RedisConnectionFactory redisConnectionFactory) {
//        this.redisConnectionFactory = redisConnectionFactory;
//    }
//
//    @Override
//    public void run(String... args) {
//
//        System.out.println("========================================");
//        System.out.println("REDIS DIAGNOSTICS");
//        System.out.println("========================================");
//
//        System.out.println("Configured Host : " + redisHost);
//        System.out.println("Configured Port : " + redisPort);
//
//        try {
//            InetAddress[] addresses = InetAddress.getAllByName(redisHost);
//
//            System.out.println("\nResolved Addresses:");
//
//            for (InetAddress address : addresses) {
//                System.out.println("  -> " + address);
//            }
//
//        } catch (Exception e) {
//            System.out.println("\nHOSTNAME RESOLUTION FAILED");
//            e.printStackTrace();
//        }
//
//        try {
//            System.out.println("\nTesting raw TCP socket...");
//
//            Socket socket = new Socket();
//
//            socket.connect(
//                    new InetSocketAddress(redisHost, redisPort),
//                    3000
//            );
//
//            System.out.println("RAW TCP CONNECTION SUCCESS");
//
//            socket.close();
//
//        } catch (Exception e) {
//            System.out.println("RAW TCP CONNECTION FAILED");
//            e.printStackTrace();
//        }
//
//        try {
//            System.out.println("\nTesting Redis PING...");
//
//            String ping =
//                    redisConnectionFactory
//                            .getConnection()
//                            .ping();
//
//            System.out.println("REDIS PING SUCCESS");
//            System.out.println("PING RESPONSE = " + ping);
//
//        } catch (Exception e) {
//            System.out.println("REDIS PING FAILED");
//            e.printStackTrace();
//        }
//
//        System.out.println("========================================");
//    }
//}