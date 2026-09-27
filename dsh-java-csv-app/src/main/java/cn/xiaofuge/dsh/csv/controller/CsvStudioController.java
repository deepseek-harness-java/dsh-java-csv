package cn.xiaofuge.dsh.csv.controller;

import cn.xiaofuge.dsh.csv.config.CsvStudioProperties;
import cn.xiaofuge.dsh.csv.model.AnalyzeRequest;
import cn.xiaofuge.dsh.csv.model.ApiException;
import cn.xiaofuge.dsh.csv.service.CsvAnalysisService;
import cn.xiaofuge.dsh.csv.service.HarnessAiStreamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.Map;

@RestController
public class CsvStudioController {
    private final CsvAnalysisService analysis;
    private final HarnessAiStreamService harnessAiStreamService;
    private final CsvStudioProperties properties;

    public CsvStudioController(CsvAnalysisService analysis,
                               HarnessAiStreamService harnessAiStreamService,
                               CsvStudioProperties properties) {
        this.analysis = analysis;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    @PostMapping("/api/csv/profile")
    public Map<String, Object> profile(@Valid @RequestBody AnalyzeRequest request) {
        return analysis.profile(analysis.resolveFile(request.path()));
    }

    @PostMapping("/api/csv/clean-plan")
    public Map<String, Object> cleanPlan(@Valid @RequestBody AnalyzeRequest request) {
        return analysis.cleanPlan(analysis.resolveFile(request.path()));
    }

    @PostMapping("/api/csv/pivot")
    public Map<String, Object> pivot(@Valid @RequestBody AnalyzeRequest request,
                                     @RequestParam(defaultValue = "") String groupBy,
                                     @RequestParam(defaultValue = "") String valueColumn) {
        Path file = analysis.resolveFile(request.path());
        if (groupBy.isBlank()) {
            // 默认取第一列分组、第二个数值列汇总
            Map<String, Object> profile = analysis.profile(file);
            java.util.List<Map<String, Object>> columns = (java.util.List<Map<String, Object>>) profile.get("columns");
            groupBy = String.valueOf(columns.get(0).get("name"));
            valueColumn = columns.stream().skip(1)
                    .filter(c -> String.valueOf(c.get("type")).matches("integer|decimal"))
                    .map(c -> String.valueOf(c.get("name"))).findFirst().orElse(groupBy);
        }
        return analysis.pivot(file, groupBy, valueColumn);
    }

    @PostMapping("/api/csv/outliers")
    public Map<String, Object> outliers(@Valid @RequestBody AnalyzeRequest request,
                                        @RequestParam(defaultValue = "") String column) {
        Path file = analysis.resolveFile(request.path());
        if (column.isBlank()) {
            Map<String, Object> profile = analysis.profile(file);
            java.util.List<Map<String, Object>> columns = (java.util.List<Map<String, Object>>) profile.get("columns");
            column = columns.stream()
                    .filter(c -> String.valueOf(c.get("type")).matches("integer|decimal"))
                    .map(c -> String.valueOf(c.get("name"))).findFirst().orElse("");
            if (column.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "未找到数值列，请指定 column 参数");
            }
        }
        return analysis.outliers(file, column);
    }

    @PostMapping("/api/csv/head")
    public Map<String, Object> head(@Valid @RequestBody AnalyzeRequest request) {
        return analysis.head(analysis.resolveFile(request.path()), 10);
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        String csvPath = request.getOrDefault("csvPath", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(csvPath, message));
    }

    private String buildPrompt(String csvPath, String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- CSV Studio（表格数据工坊，只读分析工具）\n");
        if (!csvPath.isBlank()) {
            prompt.append("- 当前 CSV 文件：").append(csvPath).append("\n");
        }
        prompt.append("- 你可以使用以下只读 CSV 工具获取真实数据（回答涉及列类型、行数、汇总、异常值的问题时，必须先调用工具查证，禁止凭空猜测）：\n");
        prompt.append("  - plugin__dsh-java-csv-plugin__csv_profile(csvPath)：列画像（类型推断/空值率/唯一值/数值范围）\n");
        prompt.append("  - plugin__dsh-java-csv-plugin__csv_clean_plan(csvPath)：清洗建议清单（不改文件）\n");
        prompt.append("  - plugin__dsh-java-csv-plugin__csv_pivot_summary(csvPath, groupBy, valueColumn)：分组汇总 count/sum/avg\n");
        prompt.append("  - plugin__dsh-java-csv-plugin__csv_outliers(csvPath, column)：IQR 离群值检测\n");
        prompt.append("  - plugin__dsh-java-csv-plugin__csv_sample_head(csvPath)：头部预览\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 引用工具结果时给出关键数字与结论，不要原样转储大段 JSON。\n");
        prompt.append("- 本产品为只读工具：不修改源文件；清洗产出建议另存新文件，且需用户确认。\n");
        return prompt.toString();
    }
}
