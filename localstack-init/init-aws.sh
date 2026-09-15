#!/bin/bash

# This script initializes AWS resources in LocalStack
echo "Initializing AWS resources in LocalStack..."

# Create SNS topic for demand response events
awslocal sns create-topic --name demand-response-events

# Create SQS queue for testing/observing messages
awslocal sqs create-queue --queue-name demand-response-events-queue

# Get the topic ARN and queue URL
TOPIC_ARN=$(awslocal sns list-topics --query "Topics[?contains(TopicArn, 'demand-response-events')].TopicArn" --output text)
QUEUE_URL=$(awslocal sqs get-queue-url --queue-name demand-response-events-queue --query "QueueUrl" --output text)
QUEUE_ARN=$(awslocal sqs get-queue-attributes --queue-url $QUEUE_URL --attribute-names QueueArn --query "Attributes.QueueArn" --output text)

# Subscribe the SQS queue to the SNS topic for easy message observation
awslocal sns subscribe \
    --topic-arn $TOPIC_ARN \
    --protocol sqs \
    --notification-endpoint $QUEUE_ARN

# Set queue policy to allow SNS to send messages
awslocal sqs set-queue-attributes \
    --queue-url $QUEUE_URL \
    --attributes '{
        "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"'$QUEUE_ARN'\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"'$TOPIC_ARN'\"}}}]}"
    }'

echo "AWS resources initialized successfully!"
echo "SNS Topic ARN: $TOPIC_ARN"
echo "SQS Queue URL: $QUEUE_URL"
