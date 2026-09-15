# Demand Response Service — Implementation Plan

## Context

Build a gRPC microservice that accepts demand response event requests and publishes one AWS SNS message per enrolled thermostat. The infrastructure (PostgreSQL, LocalStack, Spring Boot, gRPC Maven plugin, `Thermostat` entity + repository, `AwsConfig`) is already in place. The proto contract, gRPC service implementation, business logic, and tests all need to be written.

---

## Stage 1 — Proto Contract

**Status:** [x] Complete

### Definition of Done
- `src/main/proto/demand_response.proto` exists and compiles cleanly via `mvn clean compile`
- Generated Java classes appear in `target/generated-sources/protobuf/`

### Expected Outcome
A well-typed gRPC contract that other stages implement against. This is the API surface the team would review first in a real PR.

### Steps to Completion
1. Create `src/main/proto/demand_response.proto`
2. Define the package and Java options:
   - `package com.example.demandresponse;`
   - `option java_package = "com.example.demandresponse.grpc";`
   - `option java_multiple_files = true;`
3. Import `google/protobuf/timestamp.proto` for time fields
4. Define `CreateEventRequest`:
   - `int32 temperature_delta = 1;`
   - `google.protobuf.Timestamp start_time = 2;`
   - `google.protobuf.Timestamp end_time = 3;`
5. Define `CreateEventResponse`:
   - `int32 messages_published = 1;` (count of SNS messages sent)
6. Define `service DemandResponseService { rpc CreateEvent(CreateEventRequest) returns (CreateEventResponse); }`
7. Add `javax.annotation:javax.annotation-api:1.3.2` at `provided` scope to `pom.xml`. The generated
   stubs are annotated `@javax.annotation.Generated`, a class the JDK dropped in Java 11, and javac must
   resolve the annotation type even though its retention is `SOURCE`. Without it `mvn clean compile` fails
   with `cannot find symbol: class Generated`. `provided` keeps it off the runtime classpath, which is
   correct because nothing looks for it at runtime.
8. Run `mvn clean compile` — verify no errors

### Verification
```bash
mvn clean compile
# Messages land in protobuf/java, the service stub in protobuf/grpc-java:
ls target/generated-sources/protobuf/java/com/example/demandresponse/grpc/
# Shows: CreateEventRequest.java, CreateEventRequestOrBuilder.java,
#        CreateEventResponse.java, CreateEventResponseOrBuilder.java, DemandResponse.java
ls target/generated-sources/protobuf/grpc-java/com/example/demandresponse/grpc/
# Shows: DemandResponseServiceGrpc.java
```

---

## Stage 2 — Validation Logic (Unit-testable Core)

**Status:** [x] Complete

### Definition of Done
- `EventService` class exists with a public `validate(...)` method
- All unit tests in `EventServiceTest` pass: valid input passes, each invalid case throws with a meaningful message

### Expected Outcome
All business rules enforced in one place, tested in isolation without Spring context or DB. Rules:
- `temperatureDelta` must not be 0 and must be in [-5, 5]
- `startTime` must be in the future
- `endTime` must be after `startTime`
- Duration must be ≥ 15 minutes and ≤ 4 hours

### Steps to Completion
1. Create `src/main/java/com/example/demandresponse/service/EventService.java`
   - `@Service` Spring bean
   - Constructor-inject `ThermostatRepository` and `SnsClient` (needed in Stage 3, wire now). Stage 3 adds
     `ObjectMapper` and the three `@Value` properties that build the topic ARN to the same constructor.
   - Add a **public** `validate(int delta, Instant startTime, Instant endTime)` method throwing
     `IllegalArgumentException` with descriptive messages for each rule. It is public, not private, because
     `EventServiceTest` lives in `com.example.demandresponse.unit` while the service lives in
     `...demandresponse.service` — a private or package-private method is unreachable from the test, and the
     only alternatives are reflection or deferring these tests until `createEvent` exists in Stage 3.
   - Delegate the rules to private `validateTemperatureDelta` and `validateSchedule` helpers, and express the
     bounds as named constants (`MAX_ABS_TEMPERATURE_DELTA`, `MIN_EVENT_DURATION`, `MAX_EVENT_DURATION`)
   - Guard `startTime`/`endTime` against null: proto3 message fields are optional on the wire, so Stage 3
     maps an absent timestamp to null rather than to the epoch
2. Implement `EventServiceTest` (6 tests, no Spring context — collaborators are Mockito mocks):
   - `testEventCreationValidation()` — valid request, no exception thrown; also asserts the inclusive
     boundaries (±5 °F, exactly 15 minutes, exactly 4 hours) are accepted
   - `testEventCreationWithInvalidDuration()` — 10-min event → exception; 5-hour event → exception
   - `testEventCreationWithInvalidTemperatureDelta()` — delta=0 → exception; delta=6 → exception; delta=-6 → exception
   - `testEventCreationWithPastStartTime()` and `testEventCreationWithEndTimeBeforeStartTime()`
   - `testEventCreationWithMissingTimes()` — null `startTime`/`endTime` → exception naming the missing field
   - Assert on message content, not just exception type
3. Run unit tests: `mvn test -Dtest=EventServiceTest`

### Verification
```bash
mvn test -Dtest=EventServiceTest
# All tests green, no Spring context started
```

---

## Stage 3 — gRPC Service Implementation + SNS Publishing

**Status:** [x] Complete

### Definition of Done
- Application starts without errors
- A `grpcurl` call with valid parameters returns a response with `messages_published = 18`
- 18 messages appear in the LocalStack SQS queue (one per seeded thermostat)
- A `grpcurl` call with invalid parameters returns a gRPC `INVALID_ARGUMENT` error with a descriptive message

### Expected Outcome
The full happy path and error path work end-to-end against a running local environment.

### Steps to Completion
1. Add `spring-boot-starter-json` to `pom.xml`. Only `jackson-annotations` and AWS's shaded
   `third-party-jackson-core` are on the runtime classpath — there is no `jackson-databind`, so without this
   the JSON payload would have to be hand-built as a string. The starter is version-managed by the Spring Boot
   parent and gives an auto-configured `ObjectMapper` bean to inject.
2. Add `createEvent(CreateEventRequest)` returning `CreateEventResponse` to `EventService`:
   - Keep gRPC types out of this class: it takes the request, returns the response, and throws plain
     exceptions. Mapping exceptions to gRPC statuses is the transport layer's job (step 4), which leaves the
     business rules unit-testable without gRPC. (An earlier draft of this plan put `StreamObserver` and
     `StatusRuntimeException` here, which contradicted its own gRPC-layer step.)
   - Read the timestamps with `request.hasStartTime()` / `hasEndTime()`, mapping an absent field to null.
     Reading them unconditionally would turn a missing timestamp into the epoch and produce the misleading
     error `startTime must be in the future but was 1970-01-01T00:00:00Z`.
   - Call `validate(...)` and let `IllegalArgumentException` propagate
   - Call `thermostatRepository.findAll()` to get all 18 enrolled thermostats
   - For each thermostat, publish a JSON SNS message to the configured topic ARN:
     ```json
     {
       "thermostatId": "...",
       "partnerId": "...",
       "customerId": "...",
       "temperatureDelta": -2,
       "startTime": "...",
       "endTime": "..."
     }
     ```
   - Return `CreateEventResponse` with `messagesPublished = thermostats.size()`
   - Wrap a serialization or SNS failure in `EventPublishingException` (new, in the `service` package) so a
     server-side failure is distinguishable from a caller's bad request
3. Create `src/main/java/com/example/demandresponse/service/ThermostatEventMessage.java` — a record holding
   the six payload fields, serialized by `ObjectMapper`. Times are ISO-8601 strings so consumers in any
   language can read them without a shared date library.
4. Create `src/main/java/com/example/demandresponse/grpc/DemandResponseGrpcService.java`
   - Annotate with `@GrpcService` (net.devh)
   - Extend `DemandResponseServiceGrpc.DemandResponseServiceImplBase`
   - Inject `EventService`
   - Override `createEvent(...)` — delegate to `eventService.createEvent(...)`, call `responseObserver.onNext(response)` and `onCompleted()`
   - Catch `IllegalArgumentException` → `Status.INVALID_ARGUMENT` carrying the rule message
   - Catch `EventPublishingException` → `Status.INTERNAL`, logged at error level
5. Inject `@Value("${sns.topic.name}")`, `@Value("${aws.region}")` and `@Value("${aws.account-id:000000000000}")`
   in `EventService` and build the topic ARN from them. The property is `sns.topic.name` — `aws.sns.topic-name`
   does not exist in `application.yml`. The `SnsClient` already points to LocalStack.
6. Start the app: `mvn spring-boot:run`
7. Test with grpcurl:
   ```bash
   grpcurl -plaintext -d '{
     "temperatureDelta": -2,
     "startTime": "2026-09-10T10:00:00Z",
     "endTime": "2026-09-10T12:00:00Z"
   }' localhost:9090 com.example.demandresponse.DemandResponseService/CreateEvent
   ```
8. Verify SQS messages:
   ```bash
   aws --endpoint-url=http://localhost:4566 sqs receive-message \
     --queue-url http://localhost:4566/000000000000/demand-response-events-queue \
     --max-number-of-messages 10
   ```

### Substages
- [x] 3a: SNS topic ARN construction — derive ARN from region + account id + topic name, as
  `arn:aws:sns:{region}:{account-id}:{topic-name}`. Read them from `application.yml` via `@Value`: `aws.region`
  and `sns.topic.name` (**not** `aws.sns.topic-name`, which does not exist). The account id comes from
  `aws.account-id` defaulted to LocalStack's fixed `000000000000`, so a real AWS account needs only a property
  rather than a code change. Verify the ARN matches what LocalStack created by running
  `aws --endpoint-url=http://localhost:4566 sns list-topics`. (In production, resolving the ARN from an
  idempotent `createTopic` call would beat assembling it by hand.)
- [x] 3b: `EventService.createEvent(...)` implemented with SNS publishing
- [x] 3c: `DemandResponseGrpcService` wires it to the gRPC endpoint
- [x] 3d: Manual grpcurl + SQS verification

### Verification
- `"messages_published": 18` in the grpcurl response (grpcurl prints the proto field name, not the Java one)
- 18 messages in SQS, one per seeded thermostat — 18 distinct `thermostatId` values, split across partners
  5/4/4/5, each body of the shape:
  `{"thermostatId":"THERM-RH-001","partnerId":"renew_home","customerId":"CUST-001","temperatureDelta":-2,"startTime":"2026-09-10T10:00:00Z","endTime":"2026-09-10T12:00:00Z"}`
  (SNS wraps this in a `Notification` envelope; the payload is the envelope's `Message` field)
- Every invalid request returns gRPC status `INVALID_ARGUMENT` with the rule-specific message: zero delta,
  delta 6, past `startTime`, `endTime` before `startTime`, 10-minute duration, 5-hour duration, missing `startTime`
- The queue stays at 0 after rejected requests, proving nothing publishes before validation passes
- `grpcurl -plaintext localhost:9090 list` shows the service (net.devh enables reflection by default and
  `grpc-services` is already on the classpath)

---

## Stage 4 — Integration Tests

**Status:** [x] Complete

### Definition of Done
- `DemandResponseIntegrationTest` fully implemented — all tests pass via `mvn test -Dtest='**/integration/*Test'`
- Tests use Testcontainers (PostgreSQL + LocalStack) — no external infrastructure needed

### Expected Outcome
Automated, reproducible end-to-end verification that can run in CI without Docker Compose running separately.

### Steps to Completion
1. Seed the LocalStack container in `TestcontainersConfiguration`. A bare `LocalStackContainer` has **no**
   topic, queue or subscription — those come from `localstack-init/init-aws.sh`, which only the Docker Compose
   stack mounts. Without this the publish fails and there is no queue to read from. Copy that same script to
   `/etc/localstack/init/ready.d/` with `MountableFile.forHostPath(..., 0755)` rather than redefining the
   resources in Java, so the tests exercise the identical topic, queue, subscription and queue policy.
2. Override the container's wait strategy to `Wait.forLogMessage(".*AWS resources initialized successfully!.*", 1)`.
   `ready.d` scripts run *after* LocalStack logs `Ready.`, which is what the default strategy waits for, so the
   default leaves a race where the container is up but the topic does not exist yet.
3. Wire the gRPC client over an **in-process channel** (the `grpc-client-spring-boot-starter` dependency is
   already on the test classpath). These are static values, not container-derived, so they belong in
   `@SpringBootTest(properties = ...)` on the test class rather than in `DynamicPropertyRegistry`, which keeps
   `TestcontainersConfiguration` about containers only:
   - `grpc.server.port=-1` — no TCP listener, so the test cannot collide with an app already on 9090
   - `grpc.server.in-process-name=demand-response-integration-test`
   - `grpc.client.demand-response.address=in-process:demand-response-integration-test`
4. Implement `DemandResponseIntegrationTest`:
   - Inject the stub with `@GrpcClient("demand-response")`, plus `SqsClient` and `ObjectMapper`
   - `@BeforeEach` — resolve the queue URL by name and drain it, so neither test depends on the other's
     leftovers or on execution order (draining beats `purgeQueue`, which real AWS rate-limits to once a minute)
   - `testCreateEventEndToEnd()`:
     - Call `createEvent(...)` with a valid future-dated request
     - Assert `response.getMessagesPublished() == 18`
     - Receive from the queue — assert 18 messages, and 18 *distinct* `thermostatId` values, so a repeated
       device cannot pass as a fan-out. SQS returns at most 10 per call, so poll until the count arrives.
     - Unwrap each body from its SNS `Notification` envelope and assert the delta and both timestamps round-trip
   - `testEventValidationInIntegration()`:
     - Call with `temperatureDelta = 0` → assert `StatusRuntimeException` with code `INVALID_ARGUMENT`
     - Call with past `startTime` → assert `INVALID_ARGUMENT`
     - Assert both descriptions still carry the rule message after crossing gRPC
     - Assert the queue is still empty — a rejected event must publish nothing
5. Run: `mvn test -Dtest='**/integration/*Test'`

### Verification
```bash
mvn test -Dtest='**/integration/*Test'
# BUILD SUCCESS, both tests green
```
- Quote the pattern; surefire matches class names or `/`-style paths, so bare `*integration*` matches nothing
  and zsh globs it before Maven sees it.
- Confirm the Expected Outcome by running with `docker compose down` — the suite must still pass entirely on
  Testcontainers.

---

## Stage 5 — Final Polish & Submission Prep

**Status:** [x] Complete

### Definition of Done
- `mvn test` passes (all unit + integration tests)
- App starts fresh with `docker compose up -d && mvn spring-boot:run`
- All stages above marked complete in this file

### Steps to Completion
1. Mark each stage complete in this file as implementation finishes
2. Add brief inline comments only where non-obvious (e.g., SNS topic ARN construction). The review pass found
   these already in place from Stages 1-4 — ARN assembly, the absent-timestamp mapping, the `ready.d` startup
   race, and the INVALID_ARGUMENT-vs-INTERNAL split. The only change was moving `EventService`'s logger above
   the business constants, matching `DataSeeder` and `DemandResponseGrpcService`.
3. Run full test suite: `mvn clean test`
4. Do a final grpcurl smoke test against the running app, on a stack brought up from scratch
   (`docker compose down -v` first) so what gets verified is the path a fresh clone takes

### Verification
```bash
docker compose down -v && docker compose up -d   # prove the fresh-start path
mvn clean test
# BUILD SUCCESS
grpcurl -plaintext -d '{"temperatureDelta": -2, "startTime": "2026-09-10T10:00:00Z", "endTime": "2026-09-10T12:00:00Z"}' \
  localhost:9090 com.example.demandresponse.DemandResponseService/CreateEvent
# {"messages_published": 18}
```

---

## Key Files

| File | Action |
|------|--------|
| `pom.xml` | Modify — add `javax.annotation-api` (`provided`, Stage 1) and `spring-boot-starter-json` (Stage 3) |
| `src/main/proto/demand_response.proto` | Create |
| `src/main/java/.../service/EventService.java` | Create |
| `src/main/java/.../service/ThermostatEventMessage.java` | Create — record for the per-device SNS payload |
| `src/main/java/.../service/EventPublishingException.java` | Create — publish failures map to `INTERNAL` |
| `src/main/java/.../grpc/DemandResponseGrpcService.java` | Create |
| `src/test/java/.../unit/EventServiceTest.java` | Implement (stub exists) |
| `src/test/java/.../integration/DemandResponseIntegrationTest.java` | Implement (stub exists) |

## Existing Infrastructure to Reuse

- `ThermostatRepository` — `src/main/java/com/example/demandresponse/repository/ThermostatRepository.java` — call `.findAll()`
- `SnsClient` bean — from `AwsConfig`, inject directly
- `TestcontainersConfiguration` — already sets up PostgreSQL + LocalStack for integration tests
- `ObjectMapper` bean — auto-configured once `spring-boot-starter-json` is on the classpath
- `application.yml` properties: `sns.topic.name: demand-response-events`, `aws.region: us-east-1`. There is
  no `aws.sns.topic-name` property; `aws.account-id` is not in the yml either and is supplied by the
  `${aws.account-id:000000000000}` default.
