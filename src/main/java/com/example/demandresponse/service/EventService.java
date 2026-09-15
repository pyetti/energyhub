package com.example.demandresponse.service;

import com.example.demandresponse.grpc.CreateEventRequest;
import com.example.demandresponse.grpc.CreateEventResponse;
import com.example.demandresponse.model.Thermostat;
import com.example.demandresponse.repository.ThermostatRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the demand response business rules.
 * <br/>
 * A demand response event asks every enrolled thermostat to shift its setpoint for a
 * bounded window. The rules below keep an event within what is safe for a customer's
 * comfort and useful to the grid, and are enforced here before any device is contacted.
 */
@Service
public class EventService {

    private static final Logger logger = LoggerFactory.getLogger(EventService.class);

    /** Largest setpoint shift, in whole degrees Fahrenheit, we are willing to ask of a customer. */
    private static final int MAX_ABS_TEMPERATURE_DELTA = 5;

    /** Below this an event is too short for the grid to see any meaningful load shift. */
    private static final Duration MIN_EVENT_DURATION = Duration.ofMinutes(15);

    /** Above this an event becomes a comfort problem rather than a load-shaping tool. */
    private static final Duration MAX_EVENT_DURATION = Duration.ofHours(4);

    /** How many failed thermostats to name in the error; the rest are counted, so a fleet-wide
     *  outage cannot produce a gRPC status message too large to send. */
    private static final int MAX_REPORTED_FAILURES = 5;

    private final ThermostatRepository thermostatRepository;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String topicArn;

    public EventService(ThermostatRepository thermostatRepository,
                        SnsClient snsClient,
                        ObjectMapper objectMapper,
                        @Value("${aws.region}") String region,
                        @Value("${aws.account-id:000000000000}") String accountId,
                        @Value("${sns.topic.name}") String topicName) {
        this.thermostatRepository = thermostatRepository;
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        // SNS has no lookup-by-name API, so the ARN is assembled from its parts. The account id
        // defaults to LocalStack's fixed 000000000000 and is overridable for real AWS accounts.
        this.topicArn = String.format("arn:aws:sns:%s:%s:%s", region, accountId, topicName);
    }

    /**
     * Validates a requested event and fans it out to every enrolled thermostat.
     *
     * @param request the event to run
     * Publishing continues past a failing device, so one bad thermostat cannot stop the rest of the
     * fleet from receiving the event. If any device failed, the call still fails - but only after
     * every device has been attempted, and the error names what did not go out.
     *
     * @return how many messages were published - one per enrolled thermostat
     * @throws IllegalArgumentException  if the request violates a business rule
     * @throws EventPublishingException  if any message could not be published to SNS
     */
    public CreateEventResponse createEvent(CreateEventRequest request) {
        // An absent timestamp is a missing field, not the epoch, so it maps to null for validation.
        Instant startTime = request.hasStartTime() ? toInstant(request.getStartTime(), "startTime") : null;
        Instant endTime = request.hasEndTime() ? toInstant(request.getEndTime(), "endTime") : null;
        int temperatureDelta = request.getTemperatureDelta();

        validate(temperatureDelta, startTime, endTime);

        List<Thermostat> thermostats = thermostatRepository.findAll();
        List<String> failedThermostatIds = new ArrayList<>();
        for (Thermostat thermostat : thermostats) {
            try {
                publish(thermostat, temperatureDelta, startTime, endTime);
            } catch (EventPublishingException e) {
                // Keep going. Aborting here would leave an arbitrary prefix of the fleet running the
                // event, with the untried devices indistinguishable from the failed one.
                logger.error("Failed to publish the event message for thermostat {}",
                        thermostat.getThermostatId(), e);
                failedThermostatIds.add(thermostat.getThermostatId());
            }
        }

        int messagesPublished = thermostats.size() - failedThermostatIds.size();
        if (!failedThermostatIds.isEmpty()) {
            // Partial fan-out is still a failure: reporting success would hide it from any caller
            // that only checks the status code. Failed devices are not retried - see the README.
            throw new EventPublishingException(String.format(
                    "Published %d of %d demand response messages; failed for %s",
                    messagesPublished, thermostats.size(), summarize(failedThermostatIds)));
        }

        logger.info("Published {} demand response messages to {} ({} degrees F from {} to {})",
                messagesPublished, topicArn, temperatureDelta, startTime, endTime);

        return CreateEventResponse.newBuilder()
                .setMessagesPublished(messagesPublished)
                .build();
    }

    private void publish(Thermostat thermostat, int temperatureDelta, Instant startTime, Instant endTime) {
        ThermostatEventMessage message = new ThermostatEventMessage(
                thermostat.getThermostatId(),
                thermostat.getPartnerId(),
                thermostat.getCustomerId(),
                temperatureDelta,
                startTime.toString(),
                endTime.toString());

        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new EventPublishingException(
                    "Failed to serialize the event message for thermostat " + thermostat.getThermostatId(), e);
        }

        try {
            snsClient.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(payload)
                    .build());
        } catch (SdkException e) {
            throw new EventPublishingException(
                    "Failed to publish the event message for thermostat " + thermostat.getThermostatId(), e);
        }
    }

    /** Names the first few failures and counts the rest, keeping the error message a sane size. */
    private static String summarize(List<String> thermostatIds) {
        if (thermostatIds.size() <= MAX_REPORTED_FAILURES) {
            return String.join(", ", thermostatIds);
        }
        return String.join(", ", thermostatIds.subList(0, MAX_REPORTED_FAILURES))
                + " and " + (thermostatIds.size() - MAX_REPORTED_FAILURES) + " more";
    }

    /**
     * Protobuf accepts any int64 in a Timestamp, but Instant covers a narrower range, so a binary
     * client can send a value that cannot be converted. That is bad input, not a server fault, so it
     * is reported as such rather than escaping as an unmapped runtime exception.
     */
    private static Instant toInstant(Timestamp timestamp, String fieldName) {
        try {
            return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException(String.format(
                    "%s is not a representable instant (seconds=%d, nanos=%d)",
                    fieldName, timestamp.getSeconds(), timestamp.getNanos()), e);
        }
    }

    /**
     * Checks a proposed event against every business rule.
     *
     * @param temperatureDelta setpoint adjustment in whole degrees Fahrenheit
     * @param startTime        when the event begins
     * @param endTime          when the event ends
     * @throws IllegalArgumentException describing the first rule the event violates
     */
    public void validate(int temperatureDelta, Instant startTime, Instant endTime) {
        validateTemperatureDelta(temperatureDelta);
        validateSchedule(startTime, endTime);
    }

    private void validateTemperatureDelta(int temperatureDelta) {
        if (temperatureDelta == 0) {
            throw new IllegalArgumentException(
                    "temperatureDelta must not be zero - an event with no setpoint change does nothing");
        }
        // Compare against both bounds rather than Math.abs: abs(Integer.MIN_VALUE) is still
        // negative, so an abs-based check would let the most extreme value through.
        if (temperatureDelta < -MAX_ABS_TEMPERATURE_DELTA || temperatureDelta > MAX_ABS_TEMPERATURE_DELTA) {
            throw new IllegalArgumentException(String.format(
                    "temperatureDelta must be within +/- %d degrees Fahrenheit but was %d",
                    MAX_ABS_TEMPERATURE_DELTA, temperatureDelta));
        }
    }

    private void validateSchedule(Instant startTime, Instant endTime) {
        if (startTime == null) {
            throw new IllegalArgumentException("startTime is required");
        }
        if (endTime == null) {
            throw new IllegalArgumentException("endTime is required");
        }
        if (!startTime.isAfter(Instant.now())) {
            throw new IllegalArgumentException("startTime must be in the future but was " + startTime);
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException(String.format(
                    "endTime (%s) must be after startTime (%s)", endTime, startTime));
        }

        Duration duration = Duration.between(startTime, endTime);
        if (duration.compareTo(MIN_EVENT_DURATION) < 0) {
            throw new IllegalArgumentException(String.format(
                    "event duration must be at least %d minutes but was %d minutes",
                    MIN_EVENT_DURATION.toMinutes(), duration.toMinutes()));
        }
        if (duration.compareTo(MAX_EVENT_DURATION) > 0) {
            throw new IllegalArgumentException(String.format(
                    "event duration must not exceed %d hours but was %d minutes",
                    MAX_EVENT_DURATION.toHours(), duration.toMinutes()));
        }
    }
}
