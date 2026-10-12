package com.toscana_insights.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.common.BadRequestException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The single named REST connector: "JSON REST API" with NONE / BEARER / API_KEY_HEADER auth, page-number or cursor
 * pagination, JSON-pointer record mapping and an incremental cursor taken from the largest value of one field.
 * Config keys: url, authType, authHeader, recordsPath, paginationType, pageParam, pageSizeParam, pageSize,
 * cursorParam, nextCursorPath, incrementalParam, incrementalField, primaryKey, table, mapping, maxPages.
 */
@Component
public class RestConnector {
    public record Result(List<Map<String, Object>> records, String newCursor) {}

    private static final long MAX_PAGE_BYTES = 5_000_000;
    private final HttpFetcher http;
    private final ObjectMapper mapper;

    public RestConnector(HttpFetcher http, ObjectMapper mapper) { this.http = http; this.mapper = mapper; }

    public void validateConfig(JsonNode config) {
        if (text(config, "url", null) == null) throw new BadRequestException("REST source needs config.url");
        if (text(config, "table", null) == null) throw new BadRequestException("REST source needs config.table (the logical table to load)");
        http.validate(config.get("url").asText());
        String auth = text(config, "authType", "NONE");
        if (!List.of("NONE", "BEARER", "API_KEY_HEADER").contains(auth)) throw new BadRequestException("authType must be NONE, BEARER or API_KEY_HEADER");
        String paging = text(config, "paginationType", "NONE");
        if (!List.of("NONE", "PAGE", "CURSOR").contains(paging)) throw new BadRequestException("paginationType must be NONE, PAGE or CURSOR");
    }

    public void probe(JsonNode config, String secret) {
        http.get(config.get("url").asText(), authHeaders(config, secret), MAX_PAGE_BYTES);
    }

    public Result fetch(JsonNode config, String secret, String cursor) {
        String paging = text(config, "paginationType", "NONE");
        int pageSize = config.path("pageSize").asInt(100);
        int maxPages = Math.min(config.path("maxPages").asInt(50), 500);
        String recordsPath = text(config, "recordsPath", "");
        String incrementalField = text(config, "incrementalField", null);
        List<Map<String, Object>> all = new ArrayList<>();
        String next = null;
        for (int page = 1; page <= maxPages; page++) {
            UriComponentsBuilder url = UriComponentsBuilder.fromUriString(config.get("url").asText());
            if (cursor != null && text(config, "incrementalParam", null) != null) url.queryParam(config.get("incrementalParam").asText(), cursor);
            if ("PAGE".equals(paging)) {
                url.queryParam(text(config, "pageParam", "page"), page);
                url.queryParam(text(config, "pageSizeParam", "page_size"), pageSize);
            } else if ("CURSOR".equals(paging) && next != null) {
                url.queryParam(text(config, "cursorParam", "cursor"), next);
            }
            JsonNode body = parse(http.get(url.build().toUriString(), authHeaders(config, secret), MAX_PAGE_BYTES).body());
            JsonNode items = recordsPath.isEmpty() ? body : body.at(recordsPath);
            if (!items.isArray()) throw new BadRequestException("recordsPath '" + recordsPath + "' did not point to a JSON array");
            for (JsonNode item : items) all.add(toRecord(item, config.get("mapping")));
            if ("PAGE".equals(paging)) { if (items.size() < pageSize) break; }
            else if ("CURSOR".equals(paging)) {
                String n = body.at(text(config, "nextCursorPath", "/next")).asText("");
                if (n.isEmpty()) break;
                next = n;
            } else break;
        }
        return new Result(all, incrementalField == null ? null : maxOf(all, incrementalField, cursor));
    }

    private Map<String, String> authHeaders(JsonNode config, String secret) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        switch (text(config, "authType", "NONE")) {
            case "BEARER" -> headers.put("Authorization", "Bearer " + require(secret));
            case "API_KEY_HEADER" -> headers.put(text(config, "authHeader", "X-API-Key"), require(secret));
            default -> { }
        }
        return headers;
    }

    private static String require(String secret) {
        if (secret == null || secret.isBlank()) throw new BadRequestException("No credential stored for this source");
        return secret;
    }

    private JsonNode parse(byte[] body) {
        try { return mapper.readTree(body); } catch (IOException e) { throw new BadRequestException("Response was not valid JSON"); }
    }

    private static Map<String, Object> toRecord(JsonNode item, JsonNode mapping) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (mapping != null && mapping.isObject()) {
            mapping.fields().forEachRemaining(e -> out.put(e.getKey(), scalar(item.at(e.getValue().asText()))));
        } else {
            flatten("", item, out);
        }
        return out;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, Object> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> flatten(prefix.isEmpty() ? e.getKey() : prefix + "_" + e.getKey(), e.getValue(), out));
        } else if (!prefix.isEmpty()) {
            out.put(prefix, scalar(node));
        }
    }

    private static Object scalar(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.decimalValue().toPlainString();
        if (n.isValueNode()) return n.asText();
        return n.toString();
    }

    /** Largest value of the incremental column (output column name) across the batch and the previous cursor, numerically if possible. */
    private static String maxOf(List<Map<String, Object>> records, String column, String previous) {
        String best = previous;
        for (Map<String, Object> r : records) {
            Object v = r.get(column);
            if (v == null) continue;
            String s = v.toString();
            if (best == null || compare(s, best) > 0) best = s;
        }
        return best;
    }

    private static int compare(String a, String b) {
        try { return new BigDecimal(a).compareTo(new BigDecimal(b)); } catch (NumberFormatException e) { return a.compareTo(b); }
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }
}
