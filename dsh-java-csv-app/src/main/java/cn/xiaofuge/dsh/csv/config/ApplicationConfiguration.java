package cn.xiaofuge.dsh.csv.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CsvStudioProperties.class)
public class ApplicationConfiguration {
}
