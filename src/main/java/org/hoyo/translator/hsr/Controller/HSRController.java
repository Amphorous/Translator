package org.hoyo.translator.hsr.Controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/hsr")
public class HSRController {

    private final RedisTemplate redisTemplate;

    public HSRController(@Qualifier("redisTemplate") RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/relic-info/{language}/{tid}")
    public ResponseEntity<Map<String, String>> getRelicInfo(
            @PathVariable String language,
            @PathVariable String tid) {
        if (tid == null) {
            return ResponseEntity.badRequest().build();
        }
        language=language.toUpperCase();
        String artifactHashKey = "relic_config:" + tid + ":ItemName:Hash";
        String artifactHash = (String) redisTemplate.opsForValue().get(artifactHashKey);

        String artifactName = "Unknown";
        if (artifactHash != null) {
            artifactName = (String) redisTemplate.opsForValue().get("textMap"+language+":" + artifactHash);
        }

        // drop first and last digits
        int length = tid.length();
        String setId = tid.substring(1, length-1);

        String setHashKey = "relics:Sets:" + setId + ":Name";
        String setHash = (String) redisTemplate.opsForValue().get(setHashKey);

        String setName = "Unknown";
        if (setHash != null) {
            setName = (String) redisTemplate.opsForValue().get("textMap"+language+":" + setHash);
        }

        Map<String, String> response = Map.of(
                "ArtifactName", artifactName != null ? artifactName : "Translation Missing",
                "SetName", setName != null ? setName : "Translation Missing"
        );

        return ResponseEntity.ok(response);
    }
    //input as TID (5 digit number),language(currently only EN), which is found in itemConfigRelic, find it go in itemName.Hash, use that to get it from TextMapEN, output is artifact name and set name
    //to get setname, take the middle three numbers of TID, go to relics.set.TID.name gives a hash which again is located from TextMapEN
}
