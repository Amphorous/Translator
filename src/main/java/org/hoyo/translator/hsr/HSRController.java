package org.hoyo.translator.hsr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.hoyo.translator.hsr.service.HonkaiTranslateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

//@CrossOrigin
@RestController
@RequestMapping("/hsr")
@RequiredArgsConstructor
public class HSRController {

    private final HonkaiTranslateService honkaiTranslateService;
    private final ObjectMapper objectMapper;

    @GetMapping("/relic-info/{language}/{tid}")
    public ResponseEntity<Map<String, String>> getRelicInfo(@PathVariable String language, @PathVariable String tid) {
        if (tid == null) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(honkaiTranslateService.translateRelicInfo(language, tid));
    }

    @GetMapping("/localization/getlist")
    public ResponseEntity<List<Map<String, Boolean>>> getLocalizationList() throws IOException {
        Path metadataPath = Paths.get("src/main/resources/textMaps/.sync_metadata.json");
        Map<String,String> metadata = objectMapper.readValue(
                metadataPath.toFile(),
                new TypeReference<>(){}
        );

        Set<String> languages = metadata.keySet()
                .stream()
                .map(name -> name.replace(".json",""))
                .map(name -> name.replaceFirst("^TextMap",""))
                .map(name -> name.replaceAll("_[0-9]+$",""))
                .map(lang -> switch(lang){
                    case "CHS" -> "cn";
                    case "CHT" -> "tw";
                    default -> lang.toLowerCase();
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return ResponseEntity.ok(languages.stream().map(lang->Map.of(lang,true)).toList());
    }

}
