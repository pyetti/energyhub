package com.example.demandresponse.unit;

import com.example.demandresponse.grpc.CreateEventRequest;
import com.example.demandresponse.grpc.CreateEventResponse;
import com.example.demandresponse.grpc.DemandResponseGrpcService;
import com.example.demandresponse.service.EventPublishingException;
import com.example.demandresponse.service.EventService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the gRPC layer's only real responsibility: turning outcomes from
 * {@link EventService} into gRPC statuses a client can act on.
 */
public class DemandResponseGrpcServiceTest {

    private EventService eventService;
    private DemandResponseGrpcService grpcService;
    private StreamObserver<CreateEventResponse> responseObserver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        eventService = mock(EventService.class);
        grpcService = new DemandResponseGrpcService(eventService);
        responseObserver = mock(StreamObserver.class);
    }

    @Test
    public void testSuccessfulEventIsReturnedToTheCaller() {
        CreateEventResponse response = CreateEventResponse.newBuilder().setMessagesPublished(18).build();
        when(eventService.createEvent(any())).thenReturn(response);

        grpcService.createEvent(CreateEventRequest.getDefaultInstance(), responseObserver);

        verify(responseObserver).onNext(response);
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());
    }

    @Test
    public void testBrokenBusinessRuleBecomesInvalidArgument() {
        when(eventService.createEvent(any()))
                .thenThrow(new IllegalArgumentException("temperatureDelta must not be zero"));

        grpcService.createEvent(CreateEventRequest.getDefaultInstance(), responseObserver);

        Status status = captureErrorStatus();
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertEquals("temperatureDelta must not be zero", status.getDescription(),
                "the caller needs the rule they broke");
        verify(responseObserver, never()).onCompleted();
    }

    @Test
    public void testPublishFailureBecomesInternalWithTheScope() {
        when(eventService.createEvent(any()))
                .thenThrow(new EventPublishingException("Published 2 of 3 demand response messages; failed for THERM-002"));

        grpcService.createEvent(CreateEventRequest.getDefaultInstance(), responseObserver);

        Status status = captureErrorStatus();
        assertEquals(Status.Code.INTERNAL, status.getCode());
        assertEquals("Published 2 of 3 demand response messages; failed for THERM-002", status.getDescription());
    }

    /**
     * An unanticipated failure - the database being unreachable, say - must not reach the client as
     * gRPC's bare UNKNOWN, and must not carry server internals such as connection strings either.
     */
    @Test
    public void testUnexpectedFailureBecomesInternalWithoutLeakingDetail() {
        when(eventService.createEvent(any())).thenThrow(new IllegalStateException(
                "Connection refused: jdbc:postgresql://internal-db.prod:5432/demand_response"));

        grpcService.createEvent(CreateEventRequest.getDefaultInstance(), responseObserver);

        Status status = captureErrorStatus();
        assertEquals(Status.Code.INTERNAL, status.getCode(), "must not fall through to UNKNOWN");
        assertFalse(status.getDescription().contains("jdbc:postgresql"),
                "server internals must stay in the log, not the response: " + status.getDescription());
        assertFalse(status.getDescription().contains("internal-db.prod"),
                "server internals must stay in the log, not the response: " + status.getDescription());
    }

    private Status captureErrorStatus() {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(error.capture());
        return ((StatusRuntimeException) error.getValue()).getStatus();
    }
}
