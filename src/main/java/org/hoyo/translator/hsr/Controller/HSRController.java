package org.hoyo.translator.hsr.Controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin
@RestController
@RequestMapping("/hsr")
public class HSRController {

    @GetMapping("test")
    public String test(){ return "test"; }

}
