package com.example.demandresponse.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a thermostat device enrolled in the demand response program.
 * Each thermostat is associated with a customer and a partner (e.g., Renew Home, Ecoplus).
 */
@Entity
@Table(name = "thermostats")
public class Thermostat {

    @Id
    @Column(name = "thermostat_id")
    private String thermostatId;

    @Column(name = "partner_id", nullable = false)
    private String partnerId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "create_date", nullable = false)
    private Instant createDate;

    public Thermostat() {
    }

    public Thermostat(String thermostatId, String partnerId, String customerId, Instant createDate) {
        this.thermostatId = thermostatId;
        this.partnerId = partnerId;
        this.customerId = customerId;
        this.createDate = createDate;
    }

    public String getThermostatId() {
        return thermostatId;
    }

    public void setThermostatId(String thermostatId) {
        this.thermostatId = thermostatId;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(String partnerId) {
        this.partnerId = partnerId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public Instant getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Instant createDate) {
        this.createDate = createDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Thermostat that = (Thermostat) o;
        return Objects.equals(thermostatId, that.thermostatId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(thermostatId);
    }

    @Override
    public String toString() {
        return "Thermostat{" +
                "thermostatId='" + thermostatId + '\'' +
                ", partnerId='" + partnerId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", createDate=" + createDate +
                '}';
    }
}
