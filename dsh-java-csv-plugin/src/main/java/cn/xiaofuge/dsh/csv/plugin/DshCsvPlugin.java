package cn.xiaofuge.dsh.csv.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshCsvPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-csv-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshCsvPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ProfileTool(),
                new CleanPlanTool(),
                new PivotTool(),
                new OutliersTool(),
                new HeadTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("csv-studio-capabilities", 20, """
                ## DSH CSV Studio Plugin
                - 所有 CSV 工具均为只读分析，不修改源文件；清洗产出建议另存新文件且必须先征得用户确认。
                - csvPath 由对话上下文直接给出（本地 .csv/.tsv 绝对路径）；未知时提示用户在页面先填写。
                - groupBy/valueColumn/column 参数不确定时，先调 csv_profile 看列名与类型再选择，禁止猜列名。
                - 回答行数、类型、空值、汇总、离群值等问题前，必须先调用对应工具查证真实结果。
                - 数据解读给结论时附上关键数字（如"TOP3 分组占 72%"）。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__csv_")) {
                return PluginHookResult.context("DSH CSV audit: read-only analysis tool call.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_CSV_ADMIN_URL", "http://127.0.0.1:8102")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String pathJson(Map<String, Object> args, String key) {
        return "{\"path\":\"" + json(str(args, key)) + "\"}";
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private class ProfileTool extends AbstractTool {
        @Override public String name() { return "csv_profile"; }
        @Override public String description() { return "CSV 数据画像：列名、类型推断（string/integer/decimal/date）、空值率、唯一值数、数值列 min/max/avg。任何分析前先调用本工具了解数据结构。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("csvPath", stringSchema("CSV/TSV 文件本地绝对路径"))
                    .prop("adminBaseUrl", stringSchema("可选，CSV Studio 后台地址，默认 http://127.0.0.1:8102"))
                    .required("csvPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/csv/profile", pathJson(args, "csvPath"), args));
        }
    }

    private class CleanPlanTool extends AbstractTool {
        @Override public String name() { return "csv_clean_plan"; }
        @Override public String description() { return "生成清洗计划：空值列、全同值列、重复行、疑似主键等建议清单（只建议，不改文件）。用户要'清洗/整理数据'时先调用本工具并逐条确认。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("csvPath", stringSchema("CSV/TSV 文件本地绝对路径"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8102"))
                    .required("csvPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/csv/clean-plan", pathJson(args, "csvPath"), args));
        }
    }

    private class PivotTool extends AbstractTool {
        @Override public String name() { return "csv_pivot_summary"; }
        @Override public String description() { return "按列分组汇总数值列：每组 count/sum/avg（最多 50 组）。回答'按 X 分组统计 Y'类问题时调用。参数不确定时先 csv_profile。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("csvPath", stringSchema("CSV/TSV 文件本地绝对路径"))
                    .prop("groupBy", stringSchema("可选，分组列名；默认自动选第一列"))
                    .prop("valueColumn", stringSchema("可选，数值列名；默认自动选第一个数值列"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8102"))
                    .required("csvPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String path = "/api/csv/pivot?groupBy=" + java.net.URLEncoder.encode(str(args, "groupBy"), java.nio.charset.StandardCharsets.UTF_8)
                    + "&valueColumn=" + java.net.URLEncoder.encode(str(args, "valueColumn"), java.nio.charset.StandardCharsets.UTF_8);
            return ok(post(path, pathJson(args, "csvPath"), args));
        }
    }

    private class OutliersTool extends AbstractTool {
        @Override public String name() { return "csv_outliers"; }
        @Override public String description() { return "基于 IQR 检测指定数值列的离群值行（返回行号与值，最多 50 条）。column 不确定时先 csv_profile。回答'有没有异常数据'时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("csvPath", stringSchema("CSV/TSV 文件本地绝对路径"))
                    .prop("column", stringSchema("可选，数值列名；默认自动选第一个数值列"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8102"))
                    .required("csvPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String path = "/api/csv/outliers?column=" + java.net.URLEncoder.encode(str(args, "column"), java.nio.charset.StandardCharsets.UTF_8);
            return ok(post(path, pathJson(args, "csvPath"), args));
        }
    }

    private class HeadTool extends AbstractTool {
        @Override public String name() { return "csv_sample_head"; }
        @Override public String description() { return "预览 CSV 头部（默认 10 行）与总行数。了解数据长相时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("csvPath", stringSchema("CSV/TSV 文件本地绝对路径"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8102"))
                    .required("csvPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/csv/head", pathJson(args, "csvPath"), args));
        }
    }
}
