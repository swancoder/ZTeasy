package com.zte.gateway.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The executive dashboard's read API (Stage 29, ADR-029).
 *
 * <p>Authorization is entirely policy-driven: {@code AdminAuthorizationFilter}
 * enforces the {@code u2s-dashboard-*} rules on this prefix, and those rules
 * are what decide which realm role may fetch which panel — a CFO's token
 * gets {@code 403} on {@code /operations}, a CTO's on {@code /spend}. There is
 * deliberately no role check in this class: the gate decides, the app doesn't
 * (the same reasoning ADR-012 applies to the admin API).
 */
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {

    private final DashboardService service;

    DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    Mono<SummaryPanel> summary(@RequestParam(defaultValue = "720") int hours) {
        return service.summary(hours);
    }

    @GetMapping("/spend")
    Mono<SpendPanel> spend(@RequestParam(defaultValue = "30") int days) {
        return service.spend(days);
    }

    @GetMapping("/operations")
    Mono<OperationsPanel> operations(@RequestParam(defaultValue = "720") int hours) {
        return service.operations(hours);
    }

    @GetMapping("/risk")
    Mono<RiskPanel> risk(@RequestParam(defaultValue = "720") int hours) {
        return service.risk(hours);
    }

    @GetMapping("/data-protection")
    Mono<DataProtectionPanel> dataProtection() {
        return service.dataProtection();
    }
}
