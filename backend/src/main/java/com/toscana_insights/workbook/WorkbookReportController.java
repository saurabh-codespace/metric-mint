package com.toscana_insights.workbook;

import com.toscana_insights.workspace.Roles;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workbook-reports")
@PreAuthorize(Roles.MEMBER)
public class WorkbookReportController {
    private final WorkbookReportService reports;

    public WorkbookReportController(WorkbookReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public List<WorkbookReportService.WorkbookReport> list() {
        return reports.list();
    }

    @GetMapping("/{id}")
    public WorkbookReportService.WorkbookReport get(@PathVariable String id) {
        return reports.get(id);
    }
}
