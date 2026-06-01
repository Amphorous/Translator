package org.hoyo.translator.hsr;

import org.hoyo.translator.hsr.service.HonkaiTranslateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

//@CrossOrigin
@RestController
@RequestMapping("/hsr")
public class HSRController {

    private final HonkaiTranslateService honkaiTranslateService;

    public HSRController(HonkaiTranslateService honkaiTranslateService) {
        this.honkaiTranslateService = honkaiTranslateService;
    }

    @GetMapping("/relic-info/{language}/{tid}")
    public ResponseEntity<Map<String, String>> getRelicInfo(@PathVariable String language, @PathVariable String tid) {
        if (tid == null) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(honkaiTranslateService.translateRelicInfo(language, tid));
    }

}
