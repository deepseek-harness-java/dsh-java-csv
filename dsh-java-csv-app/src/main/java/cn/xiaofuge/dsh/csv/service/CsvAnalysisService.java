package cn.xiaofuge.dsh.csv.service;

import cn.xiaofuge.dsh.csv.config.CsvStudioProperties;
import cn.xiaofuge.dsh.csv.model.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CSV Studio 核心：只读解析 CSV/TSV，画像、清洗建议、透视、异常行。
 * 绝不修改源文件。
 */
@Service
public class CsvAnalysisService {
    private static final Pattern INT = Pattern.compile("^[+-]?\\d{1,18}$");
    private static final Pattern DECIMAL = Pattern.compile("^[+-]?\\d+\\.\\d+$");
    private static final Pattern DATE_LIKE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([T ].*)?$");
    private static final int MAX_ROWS = 100_000;

    private final CsvStudioProperties properties;

    public CsvAnalysisService(CsvStudioProperties properties) {
        this.properties = properties;
    }

    public Path resolveFile(String raw) {
        Path path = Path.of(raw.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 文件不存在：" + path);
        }
        String name = path.getFileName().toString().toLowerCase();
        if (!name.endsWith(".csv") && !name.endsWith(".tsv")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仅支持 .csv / .tsv 文件");
        }
        return path;
    }

    List<String[]> readAll(Path file) {
        String delimiter = file.getFileName().toString().toLowerCase().endsWith(".tsv") ? "\t" : ",";
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<String[]> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && rows.size() < MAX_ROWS) {
                rows.add(smartSplit(line, delimiter));
            }
            if (rows.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "文件为空");
            }
            return rows;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 CSV 失败：" + e.getMessage());
        }
    }

    /** 简易 CSV 分割：支持双引号转义。 */
    static String[] smartSplit(String line, String delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (!inQuotes && line.startsWith(delimiter, i)) {
                cells.add(cell.toString());
                cell.setLength(0);
                i += delimiter.length() - 1;
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }

    /** 数据画像：列数、行数、每列类型推断、空值率、唯一值数、数值列 min/max/avg。 */
    public Map<String, Object> profile(Path file) {
        List<String[]> rows = readAll(file);
        String[] header = rows.get(0);
        List<String[]> data = rows.subList(1, rows.size());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int col = 0; col < header.length; col++) {
            int empty = 0, numeric = 0, intLike = 0, dateLike = 0;
            java.util.Set<String> distinct = new java.util.HashSet<>();
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
            for (String[] row : data) {
                String value = col < row.length ? row[col].trim() : "";
                if (value.isEmpty()) { empty++; continue; }
                distinct.add(value);
                if (INT.matcher(value).matches()) {
                    numeric++; intLike++;
                    double v = Double.parseDouble(value);
                    min = Math.min(min, v); max = Math.max(max, v); sum += v;
                } else if (DECIMAL.matcher(value).matches()) {
                    numeric++;
                    double v = Double.parseDouble(value);
                    min = Math.min(min, v); max = Math.max(max, v); sum += v;
                }
                if (DATE_LIKE.matcher(value).matches()) dateLike++;
            }
            int filled = data.size() - empty;
            String type = dateLike > filled * 0.8 && filled > 0 ? "date"
                    : numeric > filled * 0.8 && filled > 0 ? (intLike > filled * 0.8 ? "integer" : "decimal")
                    : "string";
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", header[col]);
            info.put("type", type);
            info.put("emptyCount", empty);
            info.put("emptyRate", data.isEmpty() ? 0 : Math.round(empty * 1000.0 / data.size()) / 1000.0);
            info.put("distinct", distinct.size());
            if (type.equals("integer") || type.equals("decimal")) {
                Map<String, Object> num = new LinkedHashMap<>();
                num.put("min", min); num.put("max", max);
                num.put("avg", filled == 0 ? 0 : Math.round(sum / filled * 100.0) / 100.0);
                info.put("numeric", num);
            }
            columns.add(info);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", file.toString());
        result.put("header", List.of(header));
        result.put("dataRows", data.size());
        result.put("columns", columns);
        return result;
    }

    /** 清洗计划：只给建议不改文件 —— 空值列、全同列、疑似 ID 列、空行、重复行。 */
    public Map<String, Object> cleanPlan(Path file) {
        List<String[]> rows = readAll(file);
        String[] header = rows.get(0);
        List<String[]> data = rows.subList(1, rows.size());
        List<Map<String, Object>> actions = new ArrayList<>();
        long emptyRows = data.stream().filter(row -> String.join("", row).isBlank()).count();
        if (emptyRows > 0) {
            actions.add(action("drop_empty_rows", "删除 " + emptyRows + " 个完全空行", "safe"));
        }
        long dupRows = data.size() - new java.util.HashSet<>(data.stream().map(java.util.Arrays::asList).toList()).size();
        if (dupRows > 0) {
            actions.add(action("dedupe", "发现 " + dupRows + " 个重复行，建议去重", "review"));
        }
        for (int col = 0; col < header.length; col++) {
            final int c = col;
            long empty = data.stream().filter(row -> c < row.length && row[c].isBlank()).count();
            long distinct = data.stream().map(row -> c < row.length ? row[c] : "").distinct().count();
            if (empty > 0) {
                actions.add(action("fill_or_drop_column", "列 " + header[col] + " 有 " + empty + " 个空值（填充均值/众数或删除行）", "review"));
            }
            if (distinct <= 1 && !data.isEmpty()) {
                actions.add(action("drop_column", "列 " + header[col] + " 全表同值，建议删除", "safe"));
            }
            if (distinct == data.size() && data.size() > 5 && header[col].toLowerCase().contains("id")) {
                actions.add(action("primary_key", "列 " + header[col] + " 唯一值率 100%，可作主键", "info"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", file.toString());
        result.put("dataRows", data.size());
        result.put("actions", actions);
        result.put("note", "只读分析：清洗产出需在页面确认后另存新文件，不修改源文件。");
        return result;
    }

    /** 透视汇总：按指定列分组统计数值列（count/sum/avg）。 */
    public Map<String, Object> pivot(Path file, String groupBy, String valueColumn) {
        List<String[]> rows = readAll(file);
        String[] header = rows.get(0);
        List<String[]> data = rows.subList(1, rows.size());
        int g = indexOf(header, groupBy);
        int v = indexOf(header, valueColumn);
        if (g < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "分组列不存在：" + groupBy);
        if (v < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "数值列不存在：" + valueColumn);
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        for (String[] row : data) {
            String key = g < row.length ? row[g] : "(空)";
            String raw = v < row.length ? row[v].trim() : "";
            if (raw.isEmpty() || !(INT.matcher(raw).matches() || DECIMAL.matcher(raw).matches())) continue;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(Double.parseDouble(raw));
        }
        List<Map<String, Object>> summary = groups.entrySet().stream()
                .limit(50)
                .map(e -> {
                    List<Double> values = e.getValue();
                    double sum = values.stream().mapToDouble(Double::doubleValue).sum();
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put(groupBy, e.getKey());
                    item.put("count", values.size());
                    item.put("sum", Math.round(sum * 100.0) / 100.0);
                    item.put("avg", values.isEmpty() ? 0 : Math.round(sum / values.size() * 100.0) / 100.0);
                    return item;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupBy", groupBy);
        result.put("valueColumn", valueColumn);
        result.put("groups", summary);
        return result;
    }

    /** 异常行：数值列基于 IQR 找离群值。 */
    public Map<String, Object> outliers(Path file, String column) {
        List<String[]> rows = readAll(file);
        String[] header = rows.get(0);
        List<String[]> data = rows.subList(1, rows.size());
        int v = indexOf(header, column);
        if (v < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "列不存在：" + column);
        List<double[]> candidates = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            String raw = v < data.get(i).length ? data.get(i)[v].trim() : "";
            if (INT.matcher(raw).matches() || DECIMAL.matcher(raw).matches()) {
                double d = Double.parseDouble(raw);
                values.add(d);
                candidates.add(new double[]{i + 2, d}); // +2：1-based 且跳过表头
            }
        }
        if (values.size() < 4) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("column", column);
            result.put("message", "数值样本不足（<4），无法计算 IQR");
            return result;
        }
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        double q1 = percentile(sorted, 25), q3 = percentile(sorted, 75);
        double iqr = q3 - q1;
        double low = q1 - 1.5 * iqr, high = q3 + 1.5 * iqr;
        List<Map<String, Object>> found = new ArrayList<>();
        for (double[] c : candidates) {
            if (c[1] < low || c[1] > high) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rowNo", (long) c[0]);
                item.put("value", c[1]);
                found.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("column", column);
        result.put("bounds", Map.of("q1", q1, "q3", q3, "low", Math.round(low * 100.0) / 100.0, "high", Math.round(high * 100.0) / 100.0));
        result.put("outliers", found.subList(0, Math.min(50, found.size())));
        result.put("outlierCount", found.size());
        return result;
    }

    /** 头部预览。 */
    public Map<String, Object> head(Path file, int n) {
        List<String[]> rows = readAll(file);
        int count = Math.min(n <= 0 ? 10 : n, 100);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("header", List.of(rows.get(0)));
        result.put("rows", rows.subList(1, Math.min(rows.size(), count + 1))
                .stream().map(List::of).toList());
        result.put("totalRows", rows.size() - 1);
        return result;
    }

    // ---------- helpers ----------

    private int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name.trim())) return i;
        }
        return -1;
    }

    private double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        double rank = p / 100.0 * (n - 1);
        int low = (int) Math.floor(rank), high = (int) Math.ceil(rank);
        if (low == high) return sorted.get(low);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (rank - low);
    }

    private Map<String, Object> action(String type, String detail, String severity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("detail", detail);
        item.put("severity", severity);
        return item;
    }
}
