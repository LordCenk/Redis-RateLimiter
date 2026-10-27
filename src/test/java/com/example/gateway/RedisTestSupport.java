package com.example.gateway;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;

public class RedisTestSupport {

    private static String cachedHost = null;

    public static synchronized String getRedisHost() {
        if (cachedHost != null) {
            return cachedHost;
        }

        String envHost = System.getenv("REDIS_HOST");
        if (envHost != null && !envHost.isBlank()) {
            cachedHost = envHost;
            return cachedHost;
        }

        // Test 127.0.0.1
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 200);
            cachedHost = "127.0.0.1";
            return cachedHost;
        } catch (Exception ignored) {
        }

        // Try WSL IP if running under Windows WSL setup
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"wsl", "hostname", "-I"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    String wslIp = line.trim().split("\\s+")[0];
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(wslIp, 6379), 500);
                        cachedHost = wslIp;
                        return cachedHost;
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        cachedHost = "localhost";
        return cachedHost;
    }

    public static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestSupport::getRedisHost);
    }
}
