package cn.qingliao.aiservice.controller;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@cn.qingliao.common.security.RequireActor
public class AiRuntimeController {
    private final Environment environment;

    public AiRuntimeController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/api/ai/status")
    public Map<String, Object> status() {
        boolean enabled = !Arrays.asList(environment.getActiveProfiles()).contains("infra");
        return Map.of("modelEnabled", enabled);
    }
}
