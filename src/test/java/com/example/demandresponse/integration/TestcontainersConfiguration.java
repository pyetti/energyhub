package com.example.demandresponse.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

/**
 * Testcontainers configuration for integration tests.
 * Starts PostgreSQL and LocalStack containers and wires them into the Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    }

    @Bean
    LocalStackContainer localStackContainer(DynamicPropertyRegistry registry) {
        LocalStackContainer localStack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4.0"))
                .withServices(SNS, SQS)
                // A bare LocalStack has no topic or queue. Rather than duplicating those definitions here,
                // run the same init script the Docker Compose stack mounts, so tests exercise the identical
                // topic, queue, subscription and queue policy.
                .withCopyFileToContainer(
                        MountableFile.forHostPath("localstack-init/init-aws.sh", 755),
                        "/etc/localstack/init/ready.d/init-aws.sh")
                // Scripts in ready.d run *after* LocalStack logs "Ready.", which is what the default wait
                // strategy keys on. Waiting for the script's own final line instead avoids a race where the
                // container looks up but the topic does not exist yet.
                .waitingFor(Wait.forLogMessage(".*AWS resources initialized successfully!.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));

        registry.add("aws.endpoint", () -> localStack.getEndpointOverride(SNS).toString());
        registry.add("aws.region", localStack::getRegion);
        registry.add("aws.access-key-id", localStack::getAccessKey);
        registry.add("aws.secret-access-key", localStack::getSecretKey);

        return localStack;
    }
}
