# DSH CSV Studio

> 表格数据工坊 —— 指向本地 CSV/TSV，即时画像、清洗建议、分组汇总、离群值检测，AI 帮你读懂数据。

属于 [deepseek-harness-java](https://github.com/deepseek-harness-java) 独立 AI 产品矩阵（参照 `dsh-java-mysql` 形态）。

## 形态

- `dsh-java-csv-app`：独立后台（Spring Boot，端口 **8102**），只读解析本地表格
- `dsh-java-csv-plugin`：DSH Java Native 插件，向 Harness Agent 暴露 5 个只读 CSV 工具

```
浏览器 → dsh-java-csv-app(:8102) → Harness Agent(:8090) → dsh-java-csv-plugin → HTTP 回调 app 只读分析接口 → 本地 CSV 文件
```

## AI 工具（插件）

| 工具 | 说明 |
|------|------|
| `csv_profile` | 列画像：类型推断（string/integer/decimal/date）、空值率、唯一值、数值范围 |
| `csv_clean_plan` | 清洗建议清单：空值列/全同值列/重复行/疑似主键（只建议不改文件） |
| `csv_pivot_summary` | 分组汇总：按列分组 count/sum/avg |
| `csv_outliers` | IQR 离群值检测：返回异常行号与值 |
| `csv_sample_head` | 头部预览 + 总行数 |

工具名暴露为 `plugin__dsh-java-csv-plugin__<name>`。

## 安全红线（AI 绝不做）

- **不修改源文件** —— 无任何写接口，清洗产出需用户确认后另存新文件
- **不外发数据** —— 数据只在本机流转
- AI 给出的清洗建议必须逐条与用户确认

## 运行

```bash
java -jar dsh-java-csv-app/target/dsh-java-csv-app-0.1.0-SNAPSHOT.jar --server.port=8102
# 插件安装：cp jar 到 ~/.dsh/standalone/plugins/ 后 REST install + activate
```

打开 http://127.0.0.1:8102 → 填入 CSV/TSV 路径 → 右侧五面板即时分析，左侧与 AI 对话。

演示数据：`~/.dsh-java-csv/demo/orders.csv`（15 行订单，含 2 笔离群大单）。

## E2E

```bash
python3 csv_studio_e2e.py
```

覆盖 5 个用例：画像、分组汇总、离群值、清洗计划+预览、AI 对话调用工具。
