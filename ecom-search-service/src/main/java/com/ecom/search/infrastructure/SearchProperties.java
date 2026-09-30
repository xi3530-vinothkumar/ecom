package com.ecom.search.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ecom.search")
public record SearchProperties(
        @DefaultValue("products") String index,
        @DefaultValue("24h") Duration orphanTtl,
        @DefaultValue("0 17 3 * * *") String purgeCron) {}
