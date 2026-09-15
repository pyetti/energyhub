# Protocol Buffer Definitions

Place your `.proto` files in this directory.

## Example Structure

```protobuf
syntax = "proto3";

package com.example.demandresponse;

option java_multiple_files = true;
option java_package = "com.example.demandresponse.grpc";

// TODO: Define your service and messages here
// Example:
// service DemandResponseService {
//   rpc CreateEvent(CreateEventRequest) returns (CreateEventResponse);
// }
```

After creating your `.proto` files, run `mvn clean compile` to generate the Java classes.
The generated classes will be available in `target/generated-sources/protobuf/`.
