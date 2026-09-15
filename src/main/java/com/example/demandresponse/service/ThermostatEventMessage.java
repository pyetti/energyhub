package com.example.demandresponse.service;

/**
 * The SNS payload for a single device.
 * <br/>
 * One of these is published per enrolled thermostat so that downstream partner services can
 * process each device independently. Times are ISO-8601 strings so consumers written in any
 * language can read them without a shared date library.
 */
public record ThermostatEventMessage(
        String thermostatId,
        String partnerId,
        String customerId,
        int temperatureDelta,
        String startTime,
        String endTime) {
}
