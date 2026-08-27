# AI Knowledge Platform — Backend

Spring Boot 3 backend service for the Enterprise AI Knowledge Platform.

---

## What's Implemented

### Phase 1 — Backend Foundation
- Spring Boot application skeleton with Tomcat embedded server
- Health check endpoint (`GET /api/health`)
- Global exception handler with standardised JSON error responses
- CORS configuration for React frontend
- PostgreSQL connection (via Spring Data JPA + HikariCP)
- H2 in-memory database for isolated test execution

### Phase 2 — User Management
- `User` JPA entity with automatic timestamps
- `Role` enum (`USER`, `ADMIN`) stored as a readable String in the database
- BCrypt password hashing via `PasswordHashingService` (passwords are **never** stored as plaintext)
- `CreateUserRequest` and `UserResponse` DTOs — the API never exposes `passwordHash`
- `UserRepository` with Spring Data JPA derived queries
- `UserService` — all business logic (duplicate check, hashing, mapping)
- `UserController` — CRUD endpoints at `/api/users`
- Jakarta Bean Validation on all request fields
- HTTP 201, 200, 400, 404, 409 status codes for appropriate scenarios

### Phase 3 — JWT Authentication & Role-Based Access Control
- Full Spring Security architecture integration (`SecurityConfig`)
- JWT Token Generation & Verification via JJWT (`JwtService`)
- Stateless authentication filter (`JwtAuthenticationFilter`) reading token claims directly
- `POST /api/auth/login` endpoint returning signed JWT access token (`LoginResponse`)
- `GET /api/auth/me` endpoint returning current user profile
- Role-based authorization rules
- Custom JSON 401 Unauthorized and 403 Forbidden responses
- Generic authentication error handling (prevents user enumeration attacks)

### Phase 4 — Document Management
- `Document` JPA entity & `DocumentStatus` enum (`UPLOADED`, `PROCESSING`, `COMPLETED`, `FAILED`)
- PDF file upload (`POST /api/documents` via `multipart/form-data`)
- File validation rules: non-empty file, PDF MIME type (`application/pdf`), `.pdf` file extension, file size limits (10MB)
- Safe unique stored filename generation (`UUID + "_" + originalFilename`) preventing path traversal and collisions
- `FileStorageService` abstraction implemented by `LocalFileStorageService` storing files on configurable local directory
- Server-side user ownership enforcement:
  - `USER` role can only view and delete documents they own (`owner_id == user.id`)
  - `ADMIN` role can view and delete documents across all users
  - Attempting to access or delete another user's document returns `403 Forbidden`
- Physical file deletion on document deletion

### Phase 5 — Asynchronous Document Processing via Apache Kafka
- Integration of Spring Kafka (`spring-kafka` & `spring-kafka-test`)
- Ingestion decoupling: HTTP upload request stores file & metadata (`UPLOADED`), emits `DocumentUploadedEvent`, and returns `201 Created` immediately
- Lightweight event payload (`DocumentUploadedEvent`): carries reference pointers (`documentId`, `ownerId`, `storagePath`, `originalFilename`) — **no raw PDF bytes**
- Dedicated producer (`DocumentEventProducer`) publishing to configurable topic `document-uploaded`
- Background worker (`DocumentProcessingConsumer`): listens to topic, transitions status `UPLOADED` → `PROCESSING` → `COMPLETED` (or `FAILED` if missing/unreadable file)
- State-based idempotency: skips already `COMPLETED` documents if duplicate events arrive
- Comprehensive automated test suite using Embedded Kafka (47 total tests passing)

---

## 1. Prerequisites

- **Java Development Kit (JDK):** Version 21 or higher
  ```bash
  java -version
  ```
- **PostgreSQL:** Version 15 or higher (running locally or via Docker)
- **Apache Kafka (Optional for local dev, Embedded Kafka used in tests):** Version 3.x+ or Docker

---

## 2. Infrastructure Setup (Local Development)

### A. Local PostgreSQL (pgAdmin / psql)
Connect to PostgreSQL and create the database:
```sql
CREATE DATABASE ai_knowledge_db;
```

### B. Alternative: PostgreSQL via Docker
```bash
docker run --name ai-knowledge-postgres \
  -e POSTGRES_DB=ai_knowledge_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  -d postgres:16-alpine
```

### C. Local Apache Kafka via Docker (KRaft Mode — No Zookeeper Required)
```bash
docker run -d --name ai-knowledge-kafka \
  -p 9092:9092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP='CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT' \
  -e KAFKA_ADVERTISED_LISTENERS='PLAINTEXT://localhost:9092,PLAINTEXT_HOST://localhost:9092' \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_PROCESS_ROLES='broker,controller' \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS='1@localhost:9093' \
  -e KAFKA_LISTENERS='PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093' \
  -e KAFKA_INTER_BROKER_LISTENER_NAME='PLAINTEXT' \
  -e KAFKA_CONTROLLER_LISTENER_NAMES='CONTROLLER' \
  -e KAFKA_LOG_DIRS='/tmp/kraft-combined-logs' \
  -e CLUSTER_ID='MkU3OEVBNTcwNTJENDM2Qk' \
  apache/kafka:latest
```

---

## 3. Environment Variables

The application follows 12-Factor methodology and reads configuration from environment variables.

| Variable | Description | Default Value |
| :--- | :--- | :--- |
| `SERVER_PORT` | Port the Spring Boot server listens on | `8080` |
| `DB_URL` | Full JDBC connection string to PostgreSQL | `jdbc:postgresql://localhost:5432/ai_knowledge_db` |
| `DB_USERNAME` | PostgreSQL database user | `postgres` |
| `DB_PASSWORD` | PostgreSQL database password | `postgres` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed frontend origins | `http://localhost:3000,http://localhost:5173` |
| `JWT_SECRET` | HMAC-SHA256 signing key (≥ 32 chars) | `insecure-local-dev-only-secret-key...` |
| `JWT_EXPIRATION_MS` | JWT token validity duration in milliseconds | `3600000` (1 hour) |
| `FILE_UPLOAD_DIR` | Directory on disk for physical document storage | `uploads` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker bootstrap servers | `localhost:9092` |
| `KAFKA_TOPIC_DOCUMENT_UPLOADED` | Kafka topic name for uploaded documents | `document-uploaded` |

### Setting Environment Variables (PowerShell):
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="postgres"
$env:JWT_SECRET="your_strong_random_secret_key_minimum_32_chars"
$env:KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
```

---

## 4. Build and Run the Application

Navigate to the `backend` directory:
```bash
cd backend
```

### Run Tests (uses H2 & Embedded Kafka — no external Postgres or Kafka required):
```powershell
.\mvnw.cmd test
```

### Start Development Server:
```powershell
.\mvnw.cmd spring-boot:run
```

The application starts on `http://localhost:8080`.

---

## 5. Architecture & Asynchronous Ingestion Flow

### End-to-End Request & Event Flow

```
Client (HTTP Multipart Request)
  │
  ▼
Spring Boot (DocumentController / DocumentService)
  ├── 1. Store physical file on disk (uploads/)
  ├── 2. Save Document metadata in PostgreSQL (Status: UPLOADED)
  └── 3. Publish DocumentUploadedEvent to Kafka topic 'document-uploaded'
  │
  ▼
HTTP 201 Created Response returned to Client immediately (Status: UPLOADED)

  ───────────────────── (Asynchronous Kafka Boundary) ─────────────────────

Kafka Topic: document-uploaded
  │
  ▼
DocumentProcessingConsumer (@KafkaListener)
  ├── 1. Fetch Document from PostgreSQL
  ├── 2. Idempotency Check: if status == COMPLETED, skip duplicate
  ├── 3. Transition status UPLOADED → PROCESSING in PostgreSQL
  ├── 4. Verify physical file existence & readability on disk
  └── 5. Transition status PROCESSING → COMPLETED (or FAILED if unreadable)
```

---

## 6. Document Processing & Kafka Trade-offs

### Why Asynchronous Processing with Kafka is Critical
PDF processing in enterprise AI platforms involves CPU-heavy tasks: text extraction, chunking, embedding generation, and vector indexing. Performing these synchronously inside an HTTP request handler causes:
- Excessive HTTP latency (seconds to minutes per file upload)
- Servlet container thread pool exhaustion under concurrent loads
- Client HTTP request timeouts

Kafka decouples ingestion from processing, allowing instant user responses while background workers consume jobs at their own pace.

### Why PDF Bytes are NOT Sent Through Kafka
Sending binary PDF payloads over Kafka causes topic log bloat, broker RAM exhaustion, replication bottlenecks, and JVM GC spikes. Instead, we use the **Claim Check Pattern**:
- Physical file is written to storage (local disk / S3).
- `DocumentUploadedEvent` carries only metadata references (`documentId`, `ownerId`, `storagePath`, `originalFilename`).

### Database / Storage / Kafka Consistency Limitation & Outbox Pattern
**Limitation:** Upload involves 3 non-atomic steps:
1. Physical file saved to disk
2. Metadata saved to PostgreSQL
3. Event published to Kafka

If Kafka is unreachable in step 3, the database row stays `UPLOADED` without an event being published.

**Transactional Outbox Pattern (Future Enhancement):**
In production systems, `Document` and an `OutboxEvent` record are written in the *same* PostgreSQL ACID transaction. A separate process (e.g. Debezium / CDC) reads the Outbox table and publishes events to Kafka with at-least-once delivery guarantees.

---

## 7. Document Status State Machine & Idempotency

### Status Flow

```
Normal Path:
UPLOADED ──► PROCESSING ──► COMPLETED

Failure Path:
UPLOADED ──► PROCESSING ──► FAILED
```

### Idempotency Behavior
Kafka delivers messages with at-least-once semantics. If duplicate `DocumentUploadedEvent` messages arrive:
- `DocumentProcessingConsumer` checks the current status in PostgreSQL.
- If status is `COMPLETED`, the consumer logs an informational message and skips processing immediately.
- If status is `FAILED`, the worker re-evaluates the file and updates state accordingly.

---

## 8. Package Structure

```
com.enterprise.aiknowledge
├── AiKnowledgePlatformApplication.java
│
├── config
│   ├── SecurityConfig.java
│   └── WebMvcConfig.java
│
├── controller
│   ├── AuthController.java
│   ├── DocumentController.java
│   ├── HealthController.java
│   └── UserController.java
│
├── dto
│   ├── CreateUserRequest.java
│   ├── DocumentResponse.java
│   ├── HealthResponse.java
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   └── UserResponse.java
│
├── exception
│   ├── EmailAlreadyExistsException.java
│   ├── ErrorResponse.java
│   ├── GlobalExceptionHandler.java
│   ├── InvalidFileException.java
│   └── ResourceNotFoundException.java
│
├── kafka
│   ├── DocumentEventProducer.java       ← Publishes DocumentUploadedEvent
│   ├── DocumentProcessingConsumer.java   ← @KafkaListener async background worker
│   ├── DocumentUploadedEvent.java        ← Lightweight event record (no bytes)
│   └── KafkaTopicConfig.java             ← Declares 'document-uploaded' NewTopic & KafkaAdmin
│
├── model
│   ├── Document.java
│   ├── DocumentStatus.java              ← Enum: UPLOADED | PROCESSING | COMPLETED | FAILED
│   ├── Role.java
│   └── User.java
│
├── repository
│   ├── DocumentRepository.java
│   └── UserRepository.java
│
├── security
│   ├── JwtAuthenticationFilter.java
│   ├── JwtService.java
│   ├── SecurityAccessDeniedHandler.java
│   └── SecurityAuthenticationEntryPoint.java
│
└── service
    ├── AuthService.java
    ├── DocumentService.java             ← Upload, List, Get, Delete business logic + Kafka publish
    ├── FileStorageService.java
    ├── LocalFileStorageService.java
    ├── PasswordHashingService.java
    └── UserService.java
```
