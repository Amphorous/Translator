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

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

@Service
public class RedisDataLoaderService {

    private static final Logger log = LoggerFactory.getLogger(RedisDataLoaderService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisDataLoaderService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeRedisData() {

        //FILE HASHING NEEDS TO BE IMPLEMENTED TO PREVENT WAITING LIKE 5 FULL MINUTES TO LOAD SHIT ON STARTUP
        log.info("Starting Redis data import...");

        // 1. Handle flat key-value pairs (e.g., TextMapEN.json)
        loadFlatObjectToRedis("textMaps/TextMapEN.json", "textMapEN");

        // 2. Handle nested objects mapping (e.g., hsr.json or relics.json)
        loadNestedObjectToRedis("assets/hsr.json", "hsr");
        loadDeeplyFlattenedJsonToRedis("assets/relics.json", "relics");

        // 3. Handle arrays of objects (e.g., ItemConfigRelic.json)
        // We use "ID" as the identifier to construct the Redis key
        loadArrayDeeplyFlattenedToRedis("assets/ItemConfigRelic.json", "relic_config", "ID");

        log.info("Redis data import complete.");
    }

    /**
     * Loads a flat JSON object where both keys and values are simple strings.
     * Example: {"1645372": "Chinese", "147327": "English"}
     */
    private void loadFlatObjectToRedis(String filePath, String keyPrefix) {
        try (InputStream inputStream = new ClassPathResource(filePath).getInputStream()) {
            Map<String, String> data = objectMapper.readValue(inputStream, new TypeReference<Map<String, String>>() {});

            data.forEach((key, value) -> {
                String redisKey = keyPrefix + ":" + key;
                redisTemplate.opsForValue().set(redisKey, value);
            });
            log.info("Successfully loaded {} entries from {}", data.size(), filePath);
        } catch (Exception e) {
            log.error("Failed to load flat object from {}: {}", filePath, e.getMessage());
        }
    }
    /**
     * Reads a JSON file and starts the recursive flattening process.
     */
    private void loadDeeplyFlattenedJsonToRedis(String filePath, String keyPrefix) {
        try (InputStream inputStream = new ClassPathResource(filePath).getInputStream()) {
            JsonNode rootNode = objectMapper.readTree(inputStream);

            // We use an array of size 1 so the recursive method can update the total count
            int[] count = {0};

            flattenAndSaveToRedis(rootNode, keyPrefix, count);

            log.info("Successfully loaded {} deeply flattened entries from {}", count[0], filePath);
        } catch (Exception e) {
            log.error("Failed to load deep object from {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * The recursive method that digs through every layer of the JSON.
     */
    private void flattenAndSaveToRedis(JsonNode node, String currentPath, int[] count) {
        // SCENARIO 1: The current node is an Object (like "Items" or "31011")
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();

                // If the path doesn't have a colon yet, use a colon (e.g. "relics:Items")
                // Otherwise, use a dot to separate the nested fields (e.g. "relics:Items.31011")
                String separator = currentPath.contains(":") ? "." : ":";
                String newPath = currentPath + separator + field.getKey();

                // Call this exact same method again to dig one layer deeper
                flattenAndSaveToRedis(field.getValue(), newPath, count);
            }
        }
        // SCENARIO 2: The current node is an Array
        else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                // Append the array index, like "relics:Items.31011.CustomDataList[0]"
                String newPath = currentPath + "[" + i + "]";
                flattenAndSaveToRedis(node.get(i), newPath, count);
            }
        }
        // SCENARIO 3: We finally reached a bottom-level value (String, Number, Boolean)
        else if (node.isValueNode() && !node.isNull()) {
            // The digging is done. Save the accumulated path and the value to Redis.
            redisTemplate.opsForValue().set(currentPath, node.asText());
            count[0]++;
        }
    }

    /**
     * Loads a nested JSON object by converting top-level keys to Redis keys
     * and stringifying the nested objects as the Redis value.
     */
    private void loadNestedObjectToRedis(String filePath, String keyPrefix) {
        try (InputStream inputStream = new ClassPathResource(filePath).getInputStream()) {
            JsonNode rootNode = objectMapper.readTree(inputStream);

            Iterator<Map.Entry<String, JsonNode>> topLevelFields = rootNode.fields();
            int count = 0;

            // Loop through top-level keys (e.g., "zh-cn", "en")
            while (topLevelFields.hasNext()) {
                Map.Entry<String, JsonNode> topField = topLevelFields.next();
                String langKey = topField.getKey();
                JsonNode innerObject = topField.getValue();

                // Ensure the value is actually a nested object before trying to loop through it
                if (innerObject.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> innerFields = innerObject.fields();

                    // Loop through the inner keys (e.g., "trailblazer", "refine")
                    while (innerFields.hasNext()) {
                        Map.Entry<String, JsonNode> innerField = innerFields.next();
                        String termKey = innerField.getKey();

                        // Use .asText() to grab the clean string without JSON quotes
                        String termValue = innerField.getValue().asText();

                        // Construct the flattened Redis key: "prefix:lang:term"
                        String redisKey = keyPrefix + ":" + langKey + ":" + termKey;

                        redisTemplate.opsForValue().set(redisKey, termValue);
                        count++;
                    }
                }
            }
            log.info("Successfully loaded {} flattened entries from {}", count, filePath);
        } catch (Exception e) {
            log.error("Failed to load nested object from {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Loads a JSON Array by extracting a specific field (like "ID") to use as the key,
     * and storing the entire object as a stringified JSON value.
     */

    private void loadArrayDeeplyFlattenedToRedis(String filePath, String keyPrefix, String idField) {
        try (InputStream inputStream = new ClassPathResource(filePath).getInputStream()) {
            JsonNode rootArray = objectMapper.readTree(inputStream);

            int[] count = {0};

            if (rootArray.isArray()) {
                for (JsonNode itemNode : rootArray) {
                    // Make sure the item actually has the ID field we want to use (e.g., "ID")
                    if (itemNode.has(idField)) {
                        String id = itemNode.get(idField).asText();

                        // This sets our starting path. Example: "relic_config:31011"
                        String basePath = keyPrefix + ":" + id;

                        // Call the recursive method we made earlier to handle the rest!
                        flattenAndSaveToRedis(itemNode, basePath, count);
                    }
                }
                log.info("Successfully loaded {} deeply flattened entries from array in {}", count[0], filePath);
            } else {
                log.warn("Expected an array in {} but found something else.", filePath);
            }
        } catch (Exception e) {
            log.error("Failed to load array from {}: {}", filePath, e.getMessage());
        }
    }
}