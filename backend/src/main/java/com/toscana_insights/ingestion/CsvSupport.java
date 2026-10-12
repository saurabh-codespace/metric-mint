package com.toscana_insights.ingestion;

import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.ingestion.TableImporter.BadRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

final class CsvSupport {
    static final int MAX_ROWS = 200_000;

    record Table(List<String> headers, List<Map<String, Object>> records, List<BadRow> badRows) {}

    private CsvSupport() {}

    /** Reads a UTF-8 CSV with a header row. Rows whose column count differs from the header are reported as bad rows. */
    static Table read(InputStream in) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).setTrim(true).build().parse(reader)) {
            List<String> headers = null;
            List<Map<String, Object>> records = new ArrayList<>();
            List<BadRow> bad = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (headers == null) {
                    headers = new ArrayList<>(record.toList());
                    if (!headers.isEmpty()) headers.set(0, headers.get(0).replace("\uFEFF", ""));
                    if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)) throw new BadRequestException("Header row has an empty column name");
                    if (headers.stream().distinct().count() != headers.size()) throw new BadRequestException("Header row has duplicate column names");
                    continue;
                }
                if (records.size() + bad.size() >= MAX_ROWS) throw new BadRequestException("File exceeds the " + MAX_ROWS + " row limit");
                int line = (int) record.getRecordNumber() - 1;
                if (record.size() != headers.size()) {
                    bad.add(new BadRow(line, "expected " + headers.size() + " columns but found " + record.size()));
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), record.get(i));
                records.add(row);
            }
            if (headers == null) throw new BadRequestException("File is empty");
            return new Table(headers, records, bad);
        } catch (IOException | IllegalStateException e) {
            throw new BadRequestException("Could not read CSV: " + e.getMessage());
        }
    }
}
