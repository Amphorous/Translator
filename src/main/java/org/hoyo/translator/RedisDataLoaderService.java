package org.hoyo.translator;

import com.fasterxml.jackson.core.type.TypeReference;
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
        Map<String, String> textMapHashes = loadTextMapMetadata();
        Map<String, String> assetHashes = loadAssetMetadata();

        for (FileConfig config : filesToLoad) {

            String fileName = Paths.get(config.filePath())
                    .getFileName()
                    .toString();

            if (config.filePath().startsWith("textMaps/")) {

                processFileIfChanged(
                        config,
                        fileName,
                        textMapHashes.get(fileName),
                        "textmap:versions"
                );

            } else {

                processFileIfChanged(
                        config,
                        fileName,
                        assetHashes.get(fileName),
                        "asset:versions"
                );
            }
        }

        log.info("Redis data import check complete.");
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

    private Map<String, String> loadTextMapMetadata() {
        try {
            ClassPathResource resource =
                    new ClassPathResource("textMaps/.sync_metadata.json");

            return objectMapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<>() {}
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to load TextMap metadata", e);
        }
    }

    private Map<String, String> loadAssetMetadata() {
        try {
            ClassPathResource resource =
                    new ClassPathResource("assets/.asset_metadata.json");

            return objectMapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<>() {}
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to load asset metadata", e);
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

    private void processFileIfChanged(FileConfig config, String fileName, String currentHash, String redisHashKey) {

        try {

            String redisHash = (String) redisTemplate
                    .opsForHash()
                    .get(redisHashKey, fileName);

            if (currentHash.equals(redisHash)) {
                log.info("Skipping [{}]: already loaded", fileName);
                return;
            }

            loadUniversalJsonToRedis(config);

            redisTemplate.opsForHash().put(
                    redisHashKey,
                    fileName,
                    currentHash
            );

            log.info(
                    "Updated version for [{}]",
                    fileName
            );

        } catch (Exception e) {
            log.error(
                    "Failed processing {}",
                    fileName,
                    e
            );
        }
    }
}