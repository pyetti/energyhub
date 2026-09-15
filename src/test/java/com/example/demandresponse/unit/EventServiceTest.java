package com.example.demandresponse.unit;

import com.example.demandresponse.grpc.CreateEventRequest;
import com.example.demandresponse.model.Thermostat;
import com.example.demandresponse.repository.ThermostatRepository;
import com.example.demandresponse.service.EventPublishingException;
import com.example.demandresponse.service.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the demand response business rules in {@link EventService}.
 * <br/>
 * These run without a Spring context or a database - validation depends on nothing
 * but its arguments, so the collaborators are mocked and never touched.
 */
public class EventServiceTest {

    /** Far enough ahead that the event is unambiguously in the future while the test runs. */
    private static final Instant START_TIME = Instant.now().plus(Duration.ofHours(1));

    private ThermostatRepository thermostatRepository;
    private SnsClient snsClient;
    private EventService eventService;

    @BeforeEach
    public void setUp() {
        thermostatRepository = mock(ThermostatRepository.class);
        snsClient = mock(SnsClient.class);
        eventService = new EventService(
                thermostatRepository,
                snsClient,
                new ObjectMapper(),
                "us-east-1",
                "000000000000",
                "demand-response-events");
    }

    @Test
    public void testEventCreationValidation() {
        assertDoesNotThrow(() -> eventService.validate(-2, START_TIME, START_TIME.plus(Duration.ofHours(2))),
                "a 2-hour, -2F event starting an hour from now should be accepted");

        // Every rule is inclusive at its boundary.
        assertDoesNotThrow(() -> eventService.validate(-5, START_TIME, START_TIME.plus(Duration.ofMinutes(15))),
                "the minimum duration and the maximum cooling delta should be accepted");
        assertDoesNotThrow(() -> eventService.validate(5, START_TIME, START_TIME.plus(Duration.ofHours(4))),
                "the maximum duration and the maximum heating delta should be accepted");
    }

    @Test
    public void testEventCreationWithInvalidDuration() {
        IllegalArgumentException tooShort = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(-2, START_TIME, START_TIME.plus(Duration.ofMinutes(10))));
        assertTrue(tooShort.getMessage().contains("at least 15 minutes"),
                "message should name the minimum duration, but was: " + tooShort.getMessage());

        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(-2, START_TIME, START_TIME.plus(Duration.ofHours(5))));
        assertTrue(tooLong.getMessage().contains("not exceed 4 hours"),
                "message should name the maximum duration, but was: " + tooLong.getMessage());
    }

    @Test
    public void testEventCreationWithInvalidTemperatureDelta() {
        Instant endTime = START_TIME.plus(Duration.ofHours(2));

        IllegalArgumentException zeroDelta = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(0, START_TIME, endTime));
        assertTrue(zeroDelta.getMessage().contains("must not be zero"),
                "message should explain that zero is rejected, but was: " + zeroDelta.getMessage());

        IllegalArgumentException tooHot = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(6, START_TIME, endTime));
        assertTrue(tooHot.getMessage().contains("within +/- 5"),
                "message should name the allowed range, but was: " + tooHot.getMessage());

        IllegalArgumentException tooCold = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(-6, START_TIME, endTime));
        assertTrue(tooCold.getMessage().contains("within +/- 5"),
                "message should name the allowed range, but was: " + tooCold.getMessage());
    }

    /**
     * Integer.MIN_VALUE is the one input an abs-based range check lets through, because
     * Math.abs(Integer.MIN_VALUE) overflows back to itself and stays negative. Both extremes are
     * pinned here so the bound can never be re-expressed that way without a failing test.
     */
    @Test
    public void testEventCreationWithExtremeTemperatureDelta() {
        Instant endTime = START_TIME.plus(Duration.ofHours(2));

        IllegalArgumentException minValue = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(Integer.MIN_VALUE, START_TIME, endTime),
                "Integer.MIN_VALUE must not slip past the +/- 5 limit");
        assertTrue(minValue.getMessage().contains("within +/- 5"),
                "message should name the allowed range, but was: " + minValue.getMessage());

        IllegalArgumentException maxValue = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(Integer.MAX_VALUE, START_TIME, endTime),
                "Integer.MAX_VALUE must not slip past the +/- 5 limit");
        assertTrue(maxValue.getMessage().contains("within +/- 5"),
                "message should name the allowed range, but was: " + maxValue.getMessage());
    }

    @Test
    public void testEventCreationWithPastStartTime() {
        Instant pastStart = Instant.now().minus(Duration.ofHours(1));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(-2, pastStart, pastStart.plus(Duration.ofHours(2))));
        assertTrue(exception.getMessage().contains("startTime must be in the future"),
                "message should explain that the start time has passed, but was: " + exception.getMessage());
    }

    @Test
    public void testEventCreationWithEndTimeBeforeStartTime() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(-2, START_TIME, START_TIME.minus(Duration.ofHours(1))));
        assertTrue(exception.getMessage().contains("must be after startTime"),
                "message should explain the ordering rule, but was: " + exception.getMessage());
    }

    @Test
    public void testEventCreationWithMissingTimes() {
        Instant endTime = START_TIME.plus(Duration.ofHours(2));

        IllegalArgumentException missingStart = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(-2, null, endTime));
        assertTrue(missingStart.getMessage().contains("startTime is required"),
                "message should name the missing field, but was: " + missingStart.getMessage());

        IllegalArgumentException missingEnd = assertThrows(IllegalArgumentException.class,
                () -> eventService.validate(-2, START_TIME, null));
        assertTrue(missingEnd.getMessage().contains("endTime is required"),
                "message should name the missing field, but was: " + missingEnd.getMessage());
    }

    /**
     * A failure partway through the fleet must not strand the devices behind it. Every thermostat
     * should still be attempted, and the resulting error should say exactly what did not go out.
     */
    @Test
    public void testCreateEventContinuesPastAFailedThermostat() {
        when(thermostatRepository.findAll()).thenReturn(threeThermostats());
        when(snsClient.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().build())
                .thenThrow(SdkException.builder().message("SNS is unavailable").build())
                .thenReturn(PublishResponse.builder().build());

        EventPublishingException exception = assertThrows(EventPublishingException.class,
                () -> eventService.createEvent(validRequest()));

        verify(snsClient, times(3)).publish(any(PublishRequest.class));

        assertTrue(exception.getMessage().contains("Published 2 of 3"),
                "the error should report the scope of the fan-out, but was: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("THERM-002"),
                "the error should name the thermostat that failed, but was: " + exception.getMessage());
    }

    @Test
    public void testCreateEventPublishesToEveryThermostat() {
        when(thermostatRepository.findAll()).thenReturn(threeThermostats());
        when(snsClient.publish(any(PublishRequest.class))).thenReturn(PublishResponse.builder().build());

        assertEquals(3, eventService.createEvent(validRequest()).getMessagesPublished());
        verify(snsClient, times(3)).publish(any(PublishRequest.class));
    }

    /**
     * Protobuf lets a binary client put any int64 in a Timestamp, well past what Instant can hold.
     * That is a malformed request, so it must surface as bad input rather than an unmapped crash.
     */
    @Test
    public void testCreateEventRejectsAnUnrepresentableTimestamp() {
        CreateEventRequest request = CreateEventRequest.newBuilder()
                .setTemperatureDelta(-2)
                .setStartTime(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build())
                .setEndTime(toTimestamp(START_TIME.plus(Duration.ofHours(2))))
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> eventService.createEvent(request));
        assertTrue(exception.getMessage().contains("startTime"),
                "the error should name the offending field, but was: " + exception.getMessage());
    }

    private static List<Thermostat> threeThermostats() {
        return List.of(
                new Thermostat("THERM-001", "renew_home", "CUST-001", Instant.now()),
                new Thermostat("THERM-002", "renew_home", "CUST-002", Instant.now()),
                new Thermostat("THERM-003", "ecoplus", "CUST-003", Instant.now()));
    }

    private static CreateEventRequest validRequest() {
        Instant endTime = START_TIME.plus(Duration.ofHours(2));
        return CreateEventRequest.newBuilder()
                .setTemperatureDelta(-2)
                .setStartTime(toTimestamp(START_TIME))
                .setEndTime(toTimestamp(endTime))
                .build();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
