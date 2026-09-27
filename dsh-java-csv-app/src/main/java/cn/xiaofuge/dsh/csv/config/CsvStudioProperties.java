package cn.xiaofuge.dsh.csv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dsh.csv")
public record CsvStudioProperties(
        String dataDirectory,
        int commandTimeoutSeconds,
        int maxCommits,
        Harness harness
) {
    public record Harness(String baseUrl) {
    }
}
