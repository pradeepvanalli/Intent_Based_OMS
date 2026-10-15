package ai.intentoms.simulation.margin;

import ai.intentoms.platform.error.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small RFC 4180 reader for order exports (Margin Replay): quoted fields with doubled quotes, commas and line breaks
 * inside quotes, CRLF or LF line endings, an optional UTF-8 BOM, blank lines ignored. Headers are kept verbatim and
 * looked up case-insensitively (Shopify capitalises them, generic exports usually do not).
 */
public final class CsvReader {
    private CsvReader() {}

    /** One parsed file: original headers and rows keyed by the lower-cased, trimmed header. */
    public record Table(List<String> headers, List<Map<String, String>> rows) {
        public boolean has(String header) { return headers.stream().anyMatch(h -> h.trim().equalsIgnoreCase(header)); }

        /** Value of a column on a row, trimmed; empty string when absent. */
        public static String get(Map<String, String> row, String header) {
            String v = row.get(key(header));
            return v == null ? "" : v.trim();
        }

        public static String key(String header) { return header == null ? "" : header.trim().toLowerCase(Locale.ROOT); }
    }

    /** Parses the whole document; {@code maxRows} data rows at most (the file is rejected beyond that). */
    public static Table parse(String text, int maxRows) {
        if (text == null || text.isBlank()) throw new ValidationException("The CSV file is empty");
        if (text.charAt(0) == '﻿') text = text.substring(1);
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, hadQuote = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { field.append('"'); i++; } else quoted = false;
                } else field.append(c);
                continue;
            }
            switch (c) {
                case '"' -> { quoted = true; hadQuote = true; }
                case ',' -> { record.add(field.toString()); field.setLength(0); hadQuote = false; }
                case '\r' -> { }
                case '\n' -> {
                    record.add(field.toString());
                    field.setLength(0);
                    if (!(record.size() == 1 && record.get(0).isBlank() && !hadQuote)) records.add(record);
                    record = new ArrayList<>();
                    hadQuote = false;
                }
                default -> field.append(c);
            }
        }
        if (quoted) throw new ValidationException("Unterminated quoted field at the end of the CSV");
        if (field.length() > 0 || !record.isEmpty()) { record.add(field.toString()); if (!(record.size() == 1 && record.get(0).isBlank())) records.add(record); }
        if (records.isEmpty()) throw new ValidationException("The CSV file has no header row");
        List<String> headers = records.get(0).stream().map(String::trim).toList();
        if (headers.stream().allMatch(String::isBlank)) throw new ValidationException("The CSV header row is empty");
        if (records.size() - 1 > maxRows) throw new ValidationException("The CSV has " + (records.size() - 1) + " rows; at most " + maxRows + " are accepted", Map.of("rows", records.size() - 1, "max", maxRows));
        List<Map<String, String>> rows = new ArrayList<>(records.size() - 1);
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) row.put(Table.key(headers.get(c)), c < rec.size() ? rec.get(c) : "");
            rows.add(row);
        }
        return new Table(headers, rows);
    }
}
