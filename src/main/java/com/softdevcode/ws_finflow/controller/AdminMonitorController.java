package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.service.PlatformHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inherits ROLE_ADMIN authorization from SecurityConfig's /admin/** matcher.
 * No internal monitoring data is exposed through public actuator endpoints.
 */
@RestController
@RequestMapping("/admin/monitor")
@RequiredArgsConstructor
public class AdminMonitorController {
    private final PlatformHealthService health;

    @GetMapping
    public PlatformHealthService.Snapshot getSnapshot() {
        return health.getSnapshot();
    }
}
