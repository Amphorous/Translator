package org.hoyo.translator.hsr;

import lombok.RequiredArgsConstructor;
import org.hoyo.translator.hsr.service.HonkaiTranslateService;
import org.hoyo.translator.loading.DataPaths;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final DataPaths dataPaths;

    // resolves a single textmap hash -> {hash: translation}
    @GetMapping("/translate/{language}/{hash}")
    public ResponseEntity<Map<String, String>> translateHash(@PathVariable String language, @PathVariable String hash) {
        return ResponseEntity.ok(honkaiTranslateService.translateHash(language, hash));
    }

    // batch variant: body ["hash1", "hash2", ...] -> {hash1: translation, hash2: translation, ...}
    @PostMapping("/translate/{language}")
    public ResponseEntity<Map<String, String>> translateHashes(@PathVariable String language, @RequestBody List<String> hashes) {
        if (hashes == null || hashes.isEmpty() || hashes.size() > HonkaiTranslateService.MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(honkaiTranslateService.translateHashes(language, hashes));
    }

    @GetMapping("/relic-info/{language}/{tid}")
    public ResponseEntity<Map<String, String>> getRelicInfo(@PathVariable String language, @PathVariable String tid) {
        if (tid == null) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(honkaiTranslateService.translateRelicInfo(language, tid));
    }

    @GetMapping("/relic-catalog/{language}")
    public ResponseEntity<Map<String, Object>> getRelicCatalog(@PathVariable String language) {
        return ResponseEntity.ok(honkaiTranslateService.getRelicCatalog(language));
    }

    @GetMapping("/stat-names/{language}")
    public ResponseEntity<Map<String, String>> getStatNames(@PathVariable String language) {
        return ResponseEntity.ok(honkaiTranslateService.getStatNames(language));
    }

    @GetMapping("/localization/getlist")
    public ResponseEntity<List<Map<String, Boolean>>> getLocalizationList() {
        Map<String, String> metadata = dataPaths.readTextMapMetadata();

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
