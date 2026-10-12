package com.toscana_insights.report;

import com.toscana_insights.query.QueryService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/** Static exports of a query result. Static files carry their generation time; live views are re-run per viewer. */
@Component
public class ReportExporter {
    public byte[] csv(QueryService.Result result) {
        try (StringWriter out = new StringWriter(); CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecord(result.columns());
            for (Map<String, Object> row : result.rows()) {
                List<String> cells = new ArrayList<>();
                for (String c : result.columns()) cells.add(safe(row.get(c)));
                printer.printRecord(cells);
            }
            printer.flush();
            return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public byte[] pdf(String title, QueryService.Result result) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<String> lines = new ArrayList<>();
            lines.add(title);
            lines.add("Generated " + result.executedAt() + " | " + result.label() + " | source: " + result.lineage().source());
            lines.add("Metric: " + result.lineage().metricDefinition() + " (v" + result.lineage().metricVersion() + ", " + result.lineage().metricStatus() + ")");
            if (!result.lineage().filters().isEmpty()) lines.add("Filters: " + String.join("; ", result.lineage().filters()));
            result.warnings().forEach(w -> lines.add("Warning: " + w));
            lines.add("");
            lines.add(String.join(" | ", result.columns()));
            for (Map<String, Object> row : result.rows()) lines.add(String.join(" | ", result.columns().stream().map(c -> String.valueOf(row.get(c))).toList()));
            PDPageContentStream stream = null;
            float y = 0;
            for (String line : lines) {
                if (stream == null || y < 50) {
                    if (stream != null) stream.close();
                    PDPage page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    stream = new PDPageContentStream(doc, page);
                    stream.setFont(font, 9);
                    y = page.getMediaBox().getHeight() - 50;
                }
                stream.beginText();
                stream.newLineAtOffset(40, y);
                stream.showText(ascii(line.length() > 130 ? line.substring(0, 130) : line));
                stream.endText();
                y -= 13;
            }
            if (stream != null) stream.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** Neutralises spreadsheet formula injection: cells starting with = + - @ are prefixed so they stay text. */
    private static String safe(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !isNumber(s) ? "'" + s : s;
    }

    private static boolean isNumber(String s) {
        try { new java.math.BigDecimal(s); return true; } catch (NumberFormatException e) { return false; }
    }

    /** The standard PDF fonts only cover Latin-1; anything else becomes '?'. */
    private static String ascii(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= 32 && c < 127 ? c : '?');
        return b.toString();
    }
}
