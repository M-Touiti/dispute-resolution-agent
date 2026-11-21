package dev.disputeagent.investigator.bootstrap.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the generated CSV files (RFC 4180: quoted fields, doubled quotes, line breaks inside quotes). */
final class Csv {

    private Csv() {
    }

    static List<Map<String, String>> read(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " could not be read; run `uv run python generate.py` in data/", e);
        }
        List<List<String>> records = parse(text);
        List<String> header = records.getFirst();
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            if (record.size() != header.size()) {
                throw new IllegalStateException(file + ": a row has " + record.size() + " fields, the header "
                        + header.size());
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), record.get(i));
            }
            rows.add(row);
        }
        return rows;
    }

    static List<List<String>> parse(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        var field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
