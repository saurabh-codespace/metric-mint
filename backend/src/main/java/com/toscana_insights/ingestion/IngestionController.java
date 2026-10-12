package com.toscana_insights.ingestion;

import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.datasource.DataSourceService;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/data-sources")
public class IngestionController {
    private final IngestionService ingestion;
    private final DataSourceService sources;
    private final CurrentUser current;

    public IngestionController(IngestionService ingestion, DataSourceService sources, CurrentUser current) {
        this.ingestion = ingestion; this.sources = sources; this.current = current;
    }

    /** Stateless preview: header, inferred types, sample rows and bad-row report. Nothing is stored. */
    @PostMapping("/csv/preview") @PreAuthorize(Roles.ADMIN_UP)
    IngestionService.Preview preview(@RequestParam("file") MultipartFile file) { return ingestion.preview(bytes(file)); }

    @PostMapping("/{id}/upload") @PreAuthorize(Roles.ADMIN_UP)
    IngestionService.RunView upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file, @RequestParam String table,
                                    @RequestParam(defaultValue = "REPLACE") TableImporter.Mode mode) {
        return ingestion.uploadCsv(sources.get(id), table, mode, bytes(file), current.username());
    }

    @PostMapping("/{id}/run") @PreAuthorize(Roles.ADMIN_UP)
    IngestionService.RunView run(@PathVariable UUID id) { return ingestion.runNow(sources.get(id), "MANUAL", current.username()); }

    @GetMapping("/{id}/runs") @PreAuthorize(Roles.ANALYST_UP)
    List<IngestionService.RunView> runs(@PathVariable UUID id) { return ingestion.history(id); }

    private static byte[] bytes(MultipartFile file) {
        try { return file.getBytes(); } catch (IOException e) { throw new BadRequestException("Could not read the uploaded file"); }
    }
}
