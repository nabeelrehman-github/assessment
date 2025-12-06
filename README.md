# Order Management Service

A Spring Boot microservice for managing orders with support for idempotency, optimistic locking, event-driven architecture, and comprehensive observability.

## Table of Contents

- [Overview](#overview)
- [Business Features](#business-features)
- [Architecture](#architecture)
- [API Endpoints](#api-endpoints)
- [Getting Started](#getting-started)
- [Testing](#testing)
- [Observability](#observability)
- [Project Structure](#project-structure)

## Overview

This service provides a robust order management system designed for high-concurrency scenarios with strong consistency guarantees. It implements industry-standard patterns including the outbox pattern for reliable event publishing, idempotency for safe retries, and optimistic locking for concurrent updates.

### Key Capabilities

- **Order Lifecycle Management**: Create, confirm, and close orders
- **Idempotent Operations**: Safe request replay with idempotency keys
- **Optimistic Locking**: Prevent lost updates in concurrent scenarios
- **Event-Driven Architecture**: Reliable event publishing via outbox pattern
- **Multi-Tenant Support**: Tenant-scoped operations with data isolation
- **Cursor-Based Pagination**: Efficient pagination for large datasets

## Business Features

### Order States

Orders progress through three states:

1. **DRAFT** - Initial state when order is created
2. **CONFIRMED** - Order is confirmed with total amount
3. **CLOSED** - Order is closed and event is published

### Idempotency

All order operations support idempotency keys to ensure safe retries:
- **Same key + same tenant**: Returns cached response (replay protection)
- **Same key + different tenant**: Returns 409 Conflict (key collision prevention)
- **Key validity**: 1-hour window for idempotent replays

### Optimistic Locking

Order updates use version-based optimistic locking:
- Each order has a `version` field that increments on updates
- Clients must provide `If-Match` header with current version
- Stale version attempts return 409 Conflict
- Prevents lost updates in concurrent scenarios

### Event Publishing

When orders are closed, events are published via the outbox pattern:
- Event is persisted in database transaction
- Outbox publisher interface allows integration with message brokers
- Ensures reliable event delivery even if broker is temporarily unavailable

## Architecture

### Technology Stack

- **Framework**: Spring Boot 3.5.8
- **Language**: Java 21
- **Database**: H2 (in-memory for development), PostgreSQL (production-ready)
- **Observability**: OpenTelemetry
- **Build Tool**: Maven

### Design Patterns

- **Outbox Pattern**: Reliable event publishing
- **Repository Pattern**: Data access abstraction
- **Service Layer**: Business logic encapsulation
- **Filter Pattern**: Cross-cutting concerns (correlation ID)

## API Endpoints

### Create Draft Order

```http
POST /orders
Headers:
  X-Tenant-Id: <tenant-id>
  Idempotency-Key: <unique-key>
  X-Correlation-Id: <correlation-id> (optional)

Response: 201 Created
{
  "code": "201",
  "message": "success",
  "data": {
    "id": "uuid",
    "tenantId": "tenant-123",
    "status": "DRAFT",
    "version": 1,
    "createdAt": "2024-01-15T10:30:45"
  }
}
```

### Confirm Order

```http
PATCH /orders/{id}/confirm
Headers:
  X-Tenant-Id: <tenant-id>
  Idempotency-Key: <unique-key>
  If-Match: <current-version>
Body:
  {
    "totalCents": 5000
  }

Response: 200 OK
{
  "code": "200",
  "message": "success",
  "data": {
    "id": "uuid",
    "totalCents": 5000,
    "status": "CONFIRMED",
    "version": 2
  }
}
```

### Close Order

```http
POST /orders/{id}/close
Headers:
  X-Tenant-Id: <tenant-id>
  Idempotency-Key: <unique-key>

Response: 200 OK
{
  "code": "200",
  "message": "success",
  "data": {
    "id": "uuid",
    "status": "CLOSED",
    "version": 3
  }
}
```

### Fetch Orders (Paginated)

```http
GET /orders?limit=10&cursor=<base64-cursor>
Headers:
  X-Tenant-Id: <tenant-id>

Response: 200 OK
{
  "code": "200",
  "message": "success",
  "data": {
    "items": [...],
    "nextCursor": "<base64-cursor>"
  }
}
```

## Getting Started

### Prerequisites

- Java 21 or higher
- Maven 3.6+
- (Optional) Docker for PostgreSQL with Testcontainers

### Running the Application

```bash
# Clone the repository
git clone <repository-url>
cd assessment

# Build the project
mvn clean install

# Run the application
mvn spring-boot:run
```

The application will start on `http://localhost:8080` by default.

### Configuration

The application uses `application.yaml` for configuration:

```yaml
spring:
  application:
    name: assessment
  datasource:
    url: jdbc:h2:mem:testdb
    driverClassName: org.h2.Driver
    username: sa
```

For production, configure PostgreSQL:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orders
    username: postgres
    password: password
```

## Testing

### Test Suite Overview

The project includes comprehensive integration tests covering:

1. **Idempotency Tests** (2 tests)
   - Same key replay returns same order ID
   - Cross-tenant key conflict detection

2. **Optimistic Locking Tests** (2 tests)
   - Version increment on successful update
   - Stale version rejection

3. **Transactional Tests** (1 test)
   - Order close + outbox atomicity

4. **Pagination Tests** (2 tests)
   - Cursor-based pagination without duplicates
   - Edge case handling

5. **Correlation ID Tests** (2 tests)
   - Auto-generation
   - Request preservation

**Total: 10 integration tests**

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=OrderIntegrationTest

# Run with coverage
mvn test jacoco:report
```

### Test Database

Tests use H2 in-memory database for fast execution. The database schema is automatically created via JPA.

### Test Examples

#### Idempotency Test

```java
@Test
void idempotency_sameKeySameBody_returnsSameOrderId() {
    // First request creates order
    ResponseEntity<String> r1 = createOrder(key, tenant);
    String orderId1 = extractOrderId(r1);
    
    // Replay with same key
    ResponseEntity<String> r2 = createOrder(key, tenant);
    String orderId2 = extractOrderId(r2);
    
    // Same order ID returned
    assertThat(orderId1).isEqualTo(orderId2);
}
```

#### Optimistic Locking Test

```java
@Test
void optimisticLocking_correctIfMatch_bumpsVersion() {
    // Create order (version = 1)
    Order order = createOrder();
    
    // Confirm with correct If-Match
    confirmOrder(order.getId(), 1);
    
    // Version incremented
    assertThat(order.getVersion()).isEqualTo(2);
}
```

For detailed test documentation, see [TEST_DOCUMENTATION.md](TEST_DOCUMENTATION.md).

## Observability

### Correlation ID

Every request is assigned a correlation ID for distributed tracing:

- **Auto-generated**: UUID if not provided in `X-Correlation-Id` header
- **Response Header**: Always included in response as `X-Correlation-Id`
- **Log Integration**: Automatically included in all log statements via MDC

**Usage:**
```http
GET /orders
X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440000
```

### Structured Logging

All logs include structured context:

```
2024-01-15 10:30:45.123 [http-nio-8080-exec-1] INFO  [550e8400-e29b-41d4-a716-446655440000] c.f.a.s.OrderService - Creating draft order
```

Log format: `[timestamp] [thread] [level] [correlationId] logger - message`

### OpenTelemetry Tracing

Distributed tracing is enabled via OpenTelemetry:

- **Spans**: Created for all service operations
- **Attributes**: Include order ID, tenant ID, version, status
- **Error Tracking**: Exceptions recorded in spans
- **Export**: Currently configured with logging exporter (can be switched to Jaeger/Zipkin)

**Example Span:**
```
Span: order.createDraftOrder
  Attributes:
    - tenant.id: tenant-123
    - order.id: abc-123
    - order.status: DRAFT
  Duration: 45ms
```

### Outbox Publisher

Events are published via the outbox pattern:

- **Interface**: `IOutboxPublisher` for broker integration
- **Implementation**: Currently no-op (logs events)
- **Production**: Can be extended to publish to Kafka, RabbitMQ, etc.

For detailed observability documentation, see [OBSERVABILITY_GUIDE.md](OBSERVABILITY_GUIDE.md).

## Project Structure

```
src/
├── main/
│   ├── java/com/fusecode/assessment/
│   │   ├── controllers/
│   │   │   └── OrderController.java          # REST API endpoints
│   │   ├── services/
│   │   │   ├── OrderService.java            # Business logic
│   │   │   ├── IOutboxPublisher.java        # Event publisher interface
│   │   │   └── OutboxPublisherImpl.java     # Publisher implementation
│   │   ├── entities/
│   │   │   ├── OrdersEntity.java            # Order entity
│   │   │   ├── IdempotencyKeysEntity.java   # Idempotency tracking
│   │   │   └── OutboxEntity.java            # Event outbox
│   │   ├── repositories/
│   │   │   ├── OrdersRepository.java        # Order data access
│   │   │   ├── IdempotencyKeysRepository.java
│   │   │   └── OutboxRepository.java
│   │   ├── models/                          # DTOs and request/response models
│   │   ├── filters/
│   │   │   └── CorrelationIdFilter.java     # Correlation ID handling
│   │   └── config/
│   │       └── OpenTelemetryConfig.java      # Observability setup
│   └── resources/
│       ├── application.yaml                 # Application configuration
│       └── logback-spring.xml               # Logging configuration
└── test/
    └── java/com/fusecode/assessment/
        └── OrderIntegrationTest.java        # Integration tests
```

## Key Business Rules

1. **Order Creation**
   - Orders start in DRAFT status
   - Idempotency keys prevent duplicate creation
   - Tenant isolation enforced

2. **Order Confirmation**
   - Requires DRAFT status
   - Must provide correct version via If-Match
   - Sets total amount and moves to CONFIRMED

3. **Order Closing**
   - Requires CONFIRMED status
   - Creates outbox event for publishing
   - Moves to CLOSED status

4. **Pagination**
   - Cursor-based for consistent results
   - Sorted by creation date (newest first)
   - No duplicates across pages

## Error Handling

| Status Code | Scenario |
|------------|----------|
| 201 | Order created successfully |
| 200 | Idempotent replay, successful update, or query |
| 404 | Order not found |
| 409 | Idempotency conflict, stale version, or invalid status |

## Development

### Building

```bash
mvn clean package
```

### Running Locally

```bash
mvn spring-boot:run
```

### Database Console

H2 console is enabled at: `http://localhost:8080/h2-console`

## Documentation

- [TEST_DOCUMENTATION.md](TEST_DOCUMENTATION.md) - Detailed test case documentation
- [OBSERVABILITY_GUIDE.md](OBSERVABILITY_GUIDE.md) - Observability features guide
- [TEST_QUICK_REFERENCE.md](TEST_QUICK_REFERENCE.md) - Quick test reference

## License

[Specify your license here]

## Contributing

[Contributing guidelines if applicable]

