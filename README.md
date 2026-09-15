# Demand Response Service - Take Home Interview Exercise

## Overview

Welcome! This exercise simulates a real-world microservice that manages demand response events for smart thermostats. In the energy industry, demand response programs help balance grid load by adjusting thermostat settings during peak usage times.

Your task is to build a gRPC service that creates demand response events and publishes messages to partner systems via AWS SNS.

Focus first on being functional, rather than comprehensive.  We prefer a working prototype with fewer features rather than a fully implemented service.

**Time Expectation**: ~2 hours

## Domain Context

Our company works with multiple thermostat partners (Renew Home, Ecoplus, Smart Home Co, etc.) to manage demand response events. When we need to reduce energy consumption during peak times, we create demand response events that tell partner thermostats to temporarily adjust their temperature settings.

The typical flow is: we create an event → publish messages to AWS SNS for each enrolled device → downstream services (outside this exercise) consume those messages and make API calls to the respective partner APIs (Renew Home API, Ecoplus API, etc.).

### Definition of an event
From the perspective of our system an event is a set of instructions for a period of time for a set of devices meant to change the load on the energy grid in some region.
A set of instructions for a thermostat, for example, in the summer would be to increase the temperature by 2 degrees from 1 pm to 3 pm.  This would cause an air conditioning system to reduce its usage for that period of time.
For the purposes of this project the term event would be used to describe the entire flow of this happening, for all the devices indicated to be involved.

## Technical Requirements

### What's Provided

* Spring Boot project structure with dependencies configured
* PostgreSQL with Thermostat model and seed data
* Docker Compose setup for PostgreSQL and LocalStack
* AWS SNS/SQS client configuration

### What You Need to Build

Implement the gRPC service to meet the product requirements below. The codebase has infrastructure set up - explore what's provided and build on it. You'll need to define your protobuf contract, implement the service, and write tests.

## Product Requirements

The product team needs a service that can create demand response events and initiate them across our partner network. Here's what they've requested:

**Event Creation:**
- We need a gRPC endpoint that accepts an event request and sends out SNS messages to initiate the event with each enrolled device
- Each device should get its own message so downstream services can process them independently
- The service should pull all enrolled thermostats from the database and publish a message for each one

**Event Parameters:**
- Events need to specify how much to adjust the temperature (either up or down in degrees Fahrenheit)
- Events need a start and end time to define when the temperature adjustment should be active
- We only want events that are between 15 minutes and 4 hours in duration - anything shorter isn't worth it and anything longer risks customer comfort
- Temperature adjustments should be reasonable - we're thinking somewhere in the range of a few degrees, definitely not more than 5 degrees in either direction
- Events with no temperature change don't make sense and shouldn't be allowed

**Timing Constraints:**
- Events need to be scheduled for future times - we can't create events that should have already started
- Obviously the end time needs to be after the start time

**Error Handling:**
- When something goes wrong (bad input, system errors, etc.), the service should respond with appropriate error information so clients know what happened

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.8+
- Docker & Docker Compose
- (Optional) grpcurl for testing gRPC endpoints

### Setup

1. **Start infrastructure**:
   ```bash
   docker compose up -d
   ```

   This starts:
   - PostgreSQL on `localhost:5432`
   - LocalStack (SNS/SQS) on `localhost:4566`

2. **Verify LocalStack**:
   ```bash
   # List SNS topics
   aws --endpoint-url=http://localhost:4566 sns list-topics

   # Should show: demand-response-events
   ```

3. **Run the application**:
   ```bash
   mvn spring-boot:run
   ```

   The application will:
   - Start on port 8080 (Spring Boot)
   - Start gRPC server on port 9090
   - Seed PostgreSQL with 18 thermostats
   - Connect to LocalStack

### Testing Your Implementation

#### 1. Make a gRPC Request

Using **grpcurl** (recommended):

```bash
# List available services
grpcurl -plaintext localhost:9090 list

# Describe your service
grpcurl -plaintext localhost:9090 describe your.package.YourService

# Make a request (adjust based on your proto definition)
grpcurl -plaintext -d '{
  "temperatureDelta": -2,
  "startTime": "2026-01-27T10:00:00Z",
  "endTime": "2026-01-27T12:00:00Z"
}' localhost:9090 your.package.YourService/CreateEvent
```

Or implement a test client in your integration tests.

#### 2. Observe Published Messages

Check if messages were published to SNS and received by the SQS queue:

```bash
# Receive messages from the SQS queue
aws --endpoint-url=http://localhost:4566 sqs receive-message \
    --queue-url http://localhost:4566/000000000000/demand-response-events-queue \
    --max-number-of-messages 10
```

You should see one message per thermostat (18 messages for a full event).

#### 3. Run Tests

```bash
# Run all tests
mvn test

# Run only unit tests
mvn test -Dtest=*unit*

# Run only integration tests
mvn test -Dtest=*integration*
```

## Tips & Hints

1. **Start with the proto file**: Define your contract first, then implement
2. **Keep it simple**: Don't over-engineer, but aim for production level quality, something that could be built upon. A working solution is better than a complex incomplete one
3. **Test as you go**: Don't wait until the end to test
4. **Check the logs**: Spring Boot and gRPC provide helpful logging
5. **Read the docs**:
   - [gRPC Spring Boot Starter](https://yidongnan.github.io/grpc-spring-boot-starter/)
   - [Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/)
   - [AWS SDK for Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/)
6. **Use AI**: While not required, we encourage AI usage to help you get through this task faster - as long as you can speak to and vouch for the code written 

## Submission

When you're done:

1. **Ensure it runs**: Test the full flow from gRPC request to SNS messages
2. **Run tests**: Make sure all tests pass
3. **Submit**:
   - Make a pull request to the GitHub repository provided for this exercise
   - Inform us that it is ready for review

## Questions?

If anything is unclear or you get stuck on setup issues or need clarification, please reach out. We want you to spend time on the interesting parts, not fighting with Docker.

Good luck! 🚀
