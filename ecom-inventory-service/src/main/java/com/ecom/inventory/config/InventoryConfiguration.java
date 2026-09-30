package com.ecom.inventory.config;

import com.ecom.inventory.application.ReservationSweeper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class InventoryConfiguration implements SchedulingConfigurer {

    private final InventoryProperties props;
    private final ObjectProvider<ReservationSweeper> sweeper;

    public InventoryConfiguration(InventoryProperties props, ObjectProvider<ReservationSweeper> sweeper) {
        this.props = props;
        this.sweeper = sweeper;
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (props.sweepEnabled()) {
            registrar.addFixedDelayTask(() -> sweeper.getObject().sweep(), props.reservationSweepInterval());
        }
    }
}
