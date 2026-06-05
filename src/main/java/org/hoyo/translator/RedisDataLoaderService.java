package org.hoyo.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

@Service
public class RedisDataLoaderService {

    private static final Logger log = LoggerFactory.getLogger(RedisDataLoaderService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisDataLoaderService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Configuration record for each file we want to load.
     */
    public record FileConfig(String filePath, String keyPrefix, String arrayIdField) {}

    @PostConstruct
    public void initializeRedisData() {
        log.info("Starting Redis data import check...");

        // Define all files to load here
        List<FileConfig> filesToLoad = new ArrayList<>(List.of(
                new FileConfig("assets/hsr.json", "hsr", null),
                new FileConfig("assets/relics.json", "relics", null),
                new FileConfig("assets/ItemConfigRelic.json", "relic_config", "ID")
        ));

        try (Stream<Path> stream = Files.list(Paths.get("src/main/resources/textMaps"))) {

            stream.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".json"))
                    .filter(name -> !name.startsWith(".")) // skips .sync_metadata.json
                    .forEach(fileName -> {

                        String configName = fileName
                                .replace(".json", "")
                                .replaceAll("_[0-9]+$", "")
                                .replaceAll("[0-9]+$", "");

                        configName = Character.toLowerCase(configName.charAt(0))
                                + configName.substring(1);

                        filesToLoad.add(
                                new FileConfig(
                                        "textMaps/" + fileName,
                                        configName,
                                        null
                                )
                        );
                    });
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Process each file universally
        for (FileConfig config : filesToLoad) {
            loadUniversalJsonToRedis(config);
        }

        log.info("Redis data import check complete.");
    }

    /**
     * Checks the file hash before running the loading logic. [DEPRECATED BY AMIR]
     */
    private void processFileIfChanged(FileConfig config) {
        try {
            String currentHash = calculateFileHash(config.filePath());
            String hashRedisKey = "system:file_version:" + config.filePath();
            String storedHash = redisTemplate.opsForValue().get(hashRedisKey);

            if (currentHash.equals(storedHash)) {
                log.info("Skipping [{}]: File has not changed since last import.", config.filePath());
            } else {
                //log.info("Update detected for [{}]. Starting import...", config.filePath());

                loadUniversalJsonToRedis(config);

                redisTemplate.opsForValue().set(hashRedisKey, currentHash);
                log.info("Successfully updated version hash for [{}]", config.filePath());
            }
        } catch (Exception e) {
            log.error("Failed to process hash check for {}: {}", config.filePath(), e.getMessage());
        }
    }

    private void loadUniversalJsonToRedis(FileConfig config) {
        try (InputStream inputStream = new ClassPathResource(config.filePath()).getInputStream()) {
            JsonNode rootNode = objectMapper.readTree(inputStream);
            int[] count = {0};
            // This is if it's a JSON Array
            if (rootNode.isArray()) {
                for (JsonNode itemNode : rootNode) {
                    String basePath = config.keyPrefix();
                    // If we specified an ID field, append it to the prefix
                    if (config.arrayIdField() != null && itemNode.has(config.arrayIdField())) {
                        basePath += ":" + itemNode.get(config.arrayIdField()).asText();
                    }
                    flattenAndSaveToRedis(itemNode, basePath, count);
                }
            }
            // If the file starts as a JSON Object like relics or TextMaps frfr
            else if (rootNode.isObject()) {
                flattenAndSaveToRedis(rootNode, config.keyPrefix(), count);
            }
            log.info("Successfully loaded {} entries from {}", count[0], config.filePath());
        } catch (Exception e) {
            log.error("Failed to load data from {}: {}", config.filePath(), e.getMessage());
        }
    }

    //This be a recursive function that goes to the end and finds stuff
    private void flattenAndSaveToRedis(JsonNode node, String currentPath, int[] count) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String newPath = currentPath + ":" + field.getKey();
                flattenAndSaveToRedis(field.getValue(), newPath, count);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String newPath = currentPath + "[" + i + "]";
                flattenAndSaveToRedis(node.get(i), newPath, count);
            }
        } else if (node.isValueNode() && !node.isNull()) {
            redisTemplate.opsForValue().set(currentPath, node.asText());
            count[0]++;
        }
    }

    /**
     * Calculates the SHA-1 hash of a file in the classpath. [DEPRECATED BY AMIR]
     */
    private String calculateFileHash(String filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream inputStream = new ClassPathResource(filePath).getInputStream()) {
            byte[] hashBytes = digest.digest(inputStream.readAllBytes());
            return HexFormat.of().formatHex(hashBytes);
        }
    }
}