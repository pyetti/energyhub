package com.example.demandresponse.grpc;

import com.example.demandresponse.service.EventPublishingException;
import com.example.demandresponse.service.EventService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC entry point for the demand response API.
 * <br/>
 * This layer owns nothing but transport: it hands the request to {@link EventService} and
 * translates the outcome into gRPC statuses, which keeps the business rules free of any
 * knowledge of gRPC.
 */
@GrpcService
public class DemandResponseGrpcService extends DemandResponseServiceGrpc.DemandResponseServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(DemandResponseGrpcService.class);

    private final EventService eventService;

    public DemandResponseGrpcService(EventService eventService) {
        this.eventService = eventService;
    }

    @Override
    public void createEvent(CreateEventRequest request, StreamObserver<CreateEventResponse> responseObserver) {
        try {
            CreateEventResponse response = eventService.createEvent(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            // A broken business rule is the caller's problem, so the reason is safe to return.
            logger.warn("Rejected CreateEvent request: {}", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (EventPublishingException e) {
            logger.error("Failed to publish demand response event", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (RuntimeException e) {
            // Anything unanticipated - the database being down, for instance - would otherwise reach
            // the client as gRPC's bare UNKNOWN, which tells them nothing. The detail stays in the
            // log rather than the response, since it can carry connection strings and query text.
            logger.error("Unexpected failure handling CreateEvent", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error while creating the demand response event")
                    .withCause(e)
                    .asRuntimeException());
        }
    }
}
