package com.example.demandresponse.config;

import com.example.demandresponse.model.Thermostat;
import com.example.demandresponse.repository.ThermostatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * Seeds the database with sample thermostat data on application startup.
 * This data represents thermostats enrolled in the demand response program across different partners.
 */
@Configuration
public class DataSeeder {

    private static final Logger logger = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    CommandLineRunner initDatabase(ThermostatRepository repository) {
        return args -> {
            // Clear existing data to ensure consistent state
            repository.deleteAll();

            List<Thermostat> thermostats = Arrays.asList(
                    // Renew Home partner devices
                    new Thermostat("THERM-RH-001", "renew_home", "CUST-001", Instant.now().minus(180, ChronoUnit.DAYS)),
                    new Thermostat("THERM-RH-002", "renew_home", "CUST-002", Instant.now().minus(150, ChronoUnit.DAYS)),
                    new Thermostat("THERM-RH-003", "renew_home", "CUST-003", Instant.now().minus(120, ChronoUnit.DAYS)),
                    new Thermostat("THERM-RH-004", "renew_home", "CUST-004", Instant.now().minus(90, ChronoUnit.DAYS)),
                    new Thermostat("THERM-RH-005", "renew_home", "CUST-005", Instant.now().minus(60, ChronoUnit.DAYS)),

                    // Ecoplus partner devices
                    new Thermostat("THERM-EP-001", "ecoplus", "CUST-006", Instant.now().minus(200, ChronoUnit.DAYS)),
                    new Thermostat("THERM-EP-002", "ecoplus", "CUST-007", Instant.now().minus(170, ChronoUnit.DAYS)),
                    new Thermostat("THERM-EP-003", "ecoplus", "CUST-008", Instant.now().minus(140, ChronoUnit.DAYS)),
                    new Thermostat("THERM-EP-004", "ecoplus", "CUST-009", Instant.now().minus(110, ChronoUnit.DAYS)),

                    // Smart Home Co partner devices
                    new Thermostat("THERM-SH-001", "smart_home_co", "CUST-010", Instant.now().minus(210, ChronoUnit.DAYS)),
                    new Thermostat("THERM-SH-002", "smart_home_co", "CUST-011", Instant.now().minus(185, ChronoUnit.DAYS)),
                    new Thermostat("THERM-SH-003", "smart_home_co", "CUST-012", Instant.now().minus(160, ChronoUnit.DAYS)),
                    new Thermostat("THERM-SH-004", "smart_home_co", "CUST-013", Instant.now().minus(135, ChronoUnit.DAYS)),

                    // EnergyConnect partner devices
                    new Thermostat("THERM-EC-001", "energy_connect", "CUST-014", Instant.now().minus(195, ChronoUnit.DAYS)),
                    new Thermostat("THERM-EC-002", "energy_connect", "CUST-015", Instant.now().minus(165, ChronoUnit.DAYS)),
                    new Thermostat("THERM-EC-003", "energy_connect", "CUST-016", Instant.now().minus(145, ChronoUnit.DAYS)),
                    new Thermostat("THERM-EC-004", "energy_connect", "CUST-017", Instant.now().minus(115, ChronoUnit.DAYS)),
                    new Thermostat("THERM-EC-005", "energy_connect", "CUST-018", Instant.now().minus(85, ChronoUnit.DAYS))
            );

            repository.saveAll(thermostats);
            logger.info("Seeded {} thermostats into the database", thermostats.size());
            logger.info("Partners: renew_home (5), ecoplus (4), smart_home_co (4), energy_connect (5)");
        };
    }
}
