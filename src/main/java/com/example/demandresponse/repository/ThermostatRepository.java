package com.example.demandresponse.repository;

import com.example.demandresponse.model.Thermostat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for accessing Thermostat data from PostgreSQL.
 */
@Repository
public interface ThermostatRepository extends JpaRepository<Thermostat, String> {

    /**
     * Find all thermostats for a specific partner.
     */
    List<Thermostat> findByPartnerId(String partnerId);

    /**
     * Find all thermostats for a specific customer.
     */
    List<Thermostat> findByCustomerId(String customerId);
}
