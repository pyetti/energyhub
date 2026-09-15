package com.example.demandresponse.integration;

import com.example.demandresponse.grpc.CreateEventRequest;
import com.example.demandresponse.grpc.CreateEventResponse;
import com.example.demandresponse.grpc.DemandResponseServiceGrpc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the demand response service.
 * <br/>
 * PostgreSQL and LocalStack containers are started automatically via Testcontainers, so this needs no
 * Docker Compose stack running alongside it. Container configuration is defined in
 * {@link TestcontainersConfiguration}.
 * <br/>
 * The gRPC endpoint is driven over an in-process channel rather than a TCP port: it is faster, and it
 * cannot collide with an application already listening on 9090 on the developer's machine.
 */
@SpringBootTest(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=demand-response-integration-test",
        "grpc.client.demand-response.address=in-process:demand-response-integration-test"
})
@Import(TestcontainersConfiguration.class)
public class DemandResponseIntegrationTest {

    /** DataSeeder enrolls this many thermostats on every startup, so the fan-out should match. */
    private static final int ENROLLED_THERMOSTATS = 18;

    private static final String QUEUE_NAME = "demand-response-events-queue";

    @GrpcClient("demand-response")
    private DemandResponseServiceGrpc.DemandResponseServiceBlockingStub demandResponseService;

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private ObjectMapper objectMapper;

    private String queueUrl;

    @BeforeEach
    public void setUp() {
        queueUrl = sqsClient.getQueueUrl(request -> request.queueName(QUEUE_NAME)).queueUrl();
        drainQueue();
    }

    @Test
    public void testCreateEventEndToEnd() {
        Instant startTime = Instant.now().plus(Duration.ofHours(1));
        Instant endTime = startTime.plus(Duration.ofHours(2));

        CreateEventResponse response = demandResponseService.createEvent(CreateEventRequest.newBuilder()
                .setTemperatureDelta(-2)
                .setStartTime(toTimestamp(startTime))
                .setEndTime(toTimestamp(endTime))
                .build());

        assertEquals(ENROLLED_THERMOSTATS, response.getMessagesPublished(),
                "one message should be published per enrolled thermostat");

        List<JsonNode> payloads = receivePayloads(ENROLLED_THERMOSTATS);
        assertEquals(ENROLLED_THERMOSTATS, payloads.size(), "every published message should reach the queue");

        Set<String> thermostatIds = payloads.stream()
                .map(payload -> payload.get("thermostatId").asText())
                .collect(Collectors.toSet());
        assertEquals(ENROLLED_THERMOSTATS, thermostatIds.size(),
                "each thermostat should get its own message, not a repeat of another device's");

        JsonNode first = payloads.get(0);
        assertEquals(-2, first.get("temperatureDelta").asInt());
        assertEquals(startTime.toString(), first.get("startTime").asText());
        assertEquals(endTime.toString(), first.get("endTime").asText());
        assertTrue(first.hasNonNull("partnerId"), "downstream routing needs the partner");
        assertTrue(first.hasNonNull("customerId"), "downstream routing needs the customer");
    }

    @Test
    public void testEventValidationInIntegration() {
        Instant startTime = Instant.now().plus(Duration.ofHours(1));
        Instant endTime = startTime.plus(Duration.ofHours(2));

        StatusRuntimeException zeroDelta = assertThrows(StatusRuntimeException.class,
                () -> demandResponseService.createEvent(CreateEventRequest.newBuilder()
                        .setTemperatureDelta(0)
                        .setStartTime(toTimestamp(startTime))
                        .setEndTime(toTimestamp(endTime))
                        .build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, zeroDelta.getStatus().getCode());
        assertTrue(zeroDelta.getStatus().getDescription().contains("must not be zero"),
                "the rule should survive the trip over gRPC, but was: " + zeroDelta.getStatus().getDescription());

        Instant pastStart = Instant.now().minus(Duration.ofHours(1));
        StatusRuntimeException pastStartTime = assertThrows(StatusRuntimeException.class,
                () -> demandResponseService.createEvent(CreateEventRequest.newBuilder()
                        .setTemperatureDelta(-2)
                        .setStartTime(toTimestamp(pastStart))
                        .setEndTime(toTimestamp(pastStart.plus(Duration.ofHours(2))))
                        .build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, pastStartTime.getStatus().getCode());
        assertTrue(pastStartTime.getStatus().getDescription().contains("startTime must be in the future"),
                "the rule should survive the trip over gRPC, but was: " + pastStartTime.getStatus().getDescription());

        assertTrue(receiveBatch().isEmpty(), "a rejected event must not publish anything");
    }

    /**
     * Receives up to {@code expected} messages, unwrapping each from its SNS notification envelope.
     * SQS hands back at most 10 per call, so this polls until the expected count arrives or the wait runs out.
     */
    private List<JsonNode> receivePayloads(int expected) {
        List<JsonNode> payloads = new ArrayList<>();
        for (int attempt = 0; attempt < 10 && payloads.size() < expected; attempt++) {
            for (Message message : receiveBatch()) {
                payloads.add(readEnvelope(message));
                sqsClient.deleteMessage(request -> request.queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle()));
            }
        }
        return payloads;
    }

    /** SNS delivers to SQS wrapped in a notification envelope; the event itself is the Message field. */
    private JsonNode readEnvelope(Message message) {
        try {
            JsonNode envelope = objectMapper.readTree(message.body());
            return objectMapper.readTree(envelope.get("Message").asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unreadable SQS message body: " + message.body(), e);
        }
    }

    private List<Message> receiveBatch() {
        return sqsClient.receiveMessage(request -> request.queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)).messages();
    }

    private void drainQueue() {
        List<Message> batch = receiveBatch();
        while (!batch.isEmpty()) {
            for (Message message : batch) {
                sqsClient.deleteMessage(request -> request.queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle()));
            }
            batch = receiveBatch();
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
