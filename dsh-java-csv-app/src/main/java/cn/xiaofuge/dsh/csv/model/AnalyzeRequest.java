package cn.xiaofuge.dsh.csv.model;

import jakarta.validation.constraints.NotBlank;

public record AnalyzeRequest(
        @NotBlank String path
) {
}
