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
- Server-side user ownership enforcement (`USER` sees own; `ADMIN` sees all)
- Physical file deletion on document deletion

### Phase 5 — Asynchronous Document Processing via Apache Kafka
- Integration of Spring Kafka (`spring-kafka` & `spring-kafka-test`)
- Ingestion decoupling: HTTP upload request stores file & metadata (`UPLOADED`), emits `DocumentUploadedEvent`, and returns `201 Created` immediately
- Lightweight event payload (`DocumentUploadedEvent`): carries reference pointers (`documentId`, `ownerId`, `storagePath`, `originalFilename`) — **no raw PDF bytes**
- Dedicated producer (`DocumentEventProducer`) publishing to configurable topic `document-uploaded`
- Local Docker setup for PostgreSQL and Kafka in KRaft mode (`infrastructure/docker-compose.yml`)

### Phase 6 — PDF Text Extraction & Persistence
- Apache PDFBox 3.0.3 integration (`PdfTextExtractionService`)
- Extracted text storage entity (`DocumentText`) mapped to separate `document_texts` table (`@OneToOne` with `Document`)
- Text normalization: standardizes line breaks, removes redundant blank lines, trims whitespace
- Page count calculation: extracts total pages per PDF
- Asynchronous worker pipeline: `DocumentProcessingConsumer` receives `DocumentUploadedEvent`, transitions status `UPLOADED` → `PROCESSING`, extracts text using PDFBox, persists `DocumentText`, and sets status to `COMPLETED` (or `FAILED` if corrupt/missing file)
- State-based idempotency: skips duplicate processing if document is already `COMPLETED` and `DocumentText` is present
- Full automated integration test suite (50 total tests passing)

---

## 1. Architecture & End-to-End Flow

```
PDF File (Client Upload)
   │
   ▼
Spring Boot (DocumentController / DocumentService)
   ├── 1. Save physical file to disk (uploads/)
   ├── 2. Save Document metadata row in PostgreSQL (Status: UPLOADED)
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
   ├── 2. Idempotency Check: if status == COMPLETED && DocumentText present, skip duplicate
   ├── 3. Transition status UPLOADED → PROCESSING in PostgreSQL
   ├── 4. Invoke PdfTextExtractionService (Apache PDFBox)
   │        ├── Read PDF pages & extract raw text
   │        └── Normalize line breaks & whitespace
   ├── 5. Persist DocumentText entity in PostgreSQL (document_texts table)
   └── 6. Transition status PROCESSING → COMPLETED (or FAILED if unreadable/corrupt)
```

---

## 2. Technical Decisions & Rationale

### Why Apache PDFBox?
- **Native JVM Library:** Runs in-process inside the JVM with zero external CLI binary dependencies, OS installations, or paid API credits.
- **Enterprise Standard:** Apache License 2.0 open-source library supporting text stripping, page count extraction, and document structure analysis.

### Why `DocumentText` is a Separate Entity
Extracted text from long PDF documents can be megabytes in size. Storing `extractedText` directly in the main `Document` table would inflate query payloads and slow down basic metadata list endpoints (`GET /api/documents`).
Mapping `DocumentText` as a separate `@Entity` linked via a `@OneToOne` relation ensures standard document metadata queries remain fast.

### Idempotency & Duplicate Kafka Messages
Kafka delivers messages with at-least-once semantics. If duplicate `DocumentUploadedEvent` messages are delivered:
- `DocumentProcessingConsumer` checks if the `Document` status is `COMPLETED` **and** a `DocumentText` record already exists for the document.
- If true, the worker logs an informational message and skips re-processing, preventing duplicate rows or corrupted state.

---

## 3. Infrastructure & Local Development Setup

### Running Infrastructure via Docker Compose
Use the provided Compose file to run PostgreSQL 16 and Kafka (KRaft mode):
```bash
# Start PostgreSQL and Kafka
docker-compose -f infrastructure/docker-compose.yml up -d

# Inspect Kafka topic events
docker exec -it ai-knowledge-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic document-uploaded \
  --from-beginning

# Stop infrastructure
docker-compose -f infrastructure/docker-compose.yml down
```

---

## 4. Build and Test Commands

Navigate to `backend/`:
```bash
cd backend
```

### Run Full Test Suite (uses H2 & Embedded Kafka — no external Postgres/Kafka needed):
```powershell
.\mvnw.cmd test
```

### Start Development Server:
```powershell
.\mvnw.cmd spring-boot:run
```

The application starts on `http://localhost:8080`.

---

## 5. Package Structure

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
│   ├── DocumentEventProducer.java
│   ├── DocumentProcessingConsumer.java   ← Extracted text worker + state machine
│   ├── DocumentUploadedEvent.java
│   └── KafkaTopicConfig.java
│
├── model
│   ├── Document.java                     ← Metadata table ("documents")
│   ├── DocumentStatus.java               ← UPLOADED | PROCESSING | COMPLETED | FAILED
│   ├── DocumentText.java                 ← Extracted text table ("document_texts")
│   ├── Role.java
│   └── User.java
│
├── repository
│   ├── DocumentRepository.java
│   ├── DocumentTextRepository.java       ← Extracted text repository queries
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
    ├── DocumentService.java
    ├── FileStorageService.java
    ├── LocalFileStorageService.java
    ├── PasswordHashingService.java
    ├── PdfExtractionResult.java          ← Text + page count DTO
    ├── PdfTextExtractionService.java     ← Apache PDFBox extraction engine
    └── UserService.java
```
