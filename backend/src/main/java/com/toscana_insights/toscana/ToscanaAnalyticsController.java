package com.toscana_insights.toscana;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/toscana")
@org.springframework.security.access.prepost.PreAuthorize(com.toscana_insights.workspace.Roles.MEMBER)
public class ToscanaAnalyticsController {
    private final ToscanaAnalyticsService analytics;

    public ToscanaAnalyticsController(ToscanaAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    ToscanaAnalyticsService.Overview overview() { return analytics.overview(); }

    @GetMapping("/schema")
    List<ToscanaAnalyticsService.TableInfo> schema() { return analytics.schema(); }

    @GetMapping("/reports")
    List<Map<String, Object>> reports() { return analytics.reports(); }

    @PostMapping("/cache/refresh")
    @org.springframework.security.access.prepost.PreAuthorize(com.toscana_insights.workspace.Roles.ADMIN_UP)
    ToscanaAnalyticsService.Overview refresh() {
        analytics.refreshCaches();
        return analytics.overview();
    }
}
