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
│   ├── DocumentProcessingConsumer.java   ← Text extraction, chunking & embedding worker
│   ├── DocumentUploadedEvent.java
│   └── KafkaTopicConfig.java
│
├── model
│   ├── Document.java                     ← Metadata table ("documents")
│   ├── DocumentChunk.java                ← Chunks table ("document_chunks")
│   ├── DocumentChunkEmbedding.java       ← Embedding metadata table ("document_chunk_embeddings")
│   ├── DocumentStatus.java               ← UPLOADED | PROCESSING | COMPLETED | FAILED
│   ├── DocumentText.java                 ← Extracted text table ("document_texts")
│   ├── Role.java
│   └── User.java
│
├── repository
│   ├── DocumentChunkEmbeddingRepository.java ← Embedding metadata queries & lifecycle
│   ├── DocumentChunkRepository.java          ← Chunk queries & lifecycle
│   ├── DocumentRepository.java
│   ├── DocumentTextRepository.java           ← Extracted text repository queries
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
    ├── ChunkVectorDto.java               ← DTO for chunk vector & payload indexing
    ├── ChunkingService.java              ← Deterministic chunking engine
    ├── DocumentService.java              ← Upload/CRUD + cascading cleanup (DB + Qdrant)
    ├── EmbeddingService.java             ← Vector embedding interface
    ├── FileStorageService.java
    ├── GeminiEmbeddingService.java       ← Google GenAI SDK (Gemini Embedding 2)
    ├── LocalFileStorageService.java
    ├── PageText.java                     ← Page number + page text record
    ├── PasswordHashingService.java
    ├── PdfExtractionResult.java          ← Text + page count + PageText list DTO
    ├── PdfTextExtractionService.java     ← Apache PDFBox extraction engine
    ├── QdrantVectorStoreService.java     ← Qdrant vector database service (gRPC)
    ├── UserService.java
    └── VectorStoreService.java           ← Vector store abstraction interface
```

---

## Embedding Generation Architecture (`feature/embedding-generation`)

### 1. Ingestion Pipeline
```
DocumentChunk (PostgreSQL)
       │
       ▼
EmbeddingService (GeminiEmbeddingService)
       │
       ▼ [Google Gen AI SDK / gemini-embedding-2]
Embedding Vector (768 dimensions)
       │
       ▼
DocumentChunkEmbedding (PostgreSQL metadata: model, dimensions, timestamps)
       │
       ▼ [Current Phase: Qdrant Integration]
Qdrant Vector Database (Vector indexing & hybrid search)
```

### 2. Architecture & Design Rationale

| Question | Architectural Rationale |
| :--- | :--- |
| **Why embeddings are needed?** | Traditional keyword search matches exact tokens but fails on semantic concepts (e.g. searching *"automobile upkeep"* misses *"car maintenance"*). Embeddings project text into a continuous geometric vector space where semantically similar meanings cluster together. |
| **Why one vector per chunk?** | Slicing documents into ~800-character chunks preserves granular concepts. An embedding of a 100-page document compresses too much meaning into one point (semantic dilution), while chunk embeddings allow pinpoint retrieval. |
| **Why 768 dimensions?** | 768 dimensions strike an optimal balance between expressive semantic representation, storage efficiency, and search latency. Supported natively by `gemini-embedding-2` via `outputDimensionality`. |
| **Why is the model configurable?** | Decoupling the model (`gemini.embedding.model`) and dimensions (`gemini.embedding.dimensions`) via `application.yml` allows seamless upgrades (e.g. to future Gemini versions or local models) without modifying ingestion code. |
| **Why is embedding generation asynchronous?** | Generating embeddings requires remote network calls that take 100ms–1000ms. Running this inside the background Kafka consumer (`DocumentProcessingConsumer`) guarantees the upload HTTP API (`POST /api/documents`) remains blazing fast (~20ms). |
| **Why are vectors not stored in PostgreSQL?** | High-dimensional float vectors consume gigabytes of table storage, degrade relational cache efficiency, and cause row bloat. PostgreSQL stores lightweight metadata (`DocumentChunkEmbedding`), while Qdrant is optimized specifically for vector indexing (HNSW graphs). |
| **Why Qdrant stores vectors?** | Specialized vector databases like Qdrant provide Approximate Nearest Neighbor (ANN) search with sub-millisecond latency and hardware-accelerated distance metrics (Cosine, Dot product). |
| **Why incompatible models cannot be mixed?** | Vectors from different models (or even different dimensions of the same model) exist in entirely different geometric coordinate spaces. Calculating cosine similarity between a vector from Model A and Model B yields meaningless mathematical garbage. |
| **Free-Tier & Zero-Cost Guarantee** | Uses the Gemini Developer API free tier. Automated test suites run 100% offline using mocks and Embedded Kafka, requiring ₹0 and no API key. |
| **Data Privacy Policy** | Only the plain text of individual `DocumentChunk` records is transmitted to the Gemini Embedding API. Passwords, JWTs, user profiles, and database credentials are never transmitted or logged. |
| **Bounded Retry Strategy** | Transient errors (HTTP 429 rate limits, 503 unavailable, network timeouts) are retried with exponential backoff up to `gemini.embedding.max-retries` (default 3). Permanent errors (400 Bad Request, 401 Unauthorized, dimension mismatches) fail immediately without retrying. |
| **Re-Embedding Strategy** | When changing models or dimensions, existing embeddings must not be silently overwritten. Instead, the collection must be re-indexed cleanly (`deleteByDocumentChunkDocumentId`), preventing incompatible vector spaces from polluting the index. |

---

## Qdrant Vector Database Integration (`feature/qdrant-integration`)

### 1. Vector Point Architecture
```
DocumentProcessingConsumer (@KafkaListener)
       ├── ... (PDF text extraction -> DocumentText -> Chunking -> Embeddings)
       ├── Save DocumentChunkEmbedding metadata in PostgreSQL
       └── Upsert vector points to Qdrant (via VectorStoreService)
                 │
                 ▼ [Collection: document_chunks]
                 Point {
                   id: chunkId,                     ← Deterministic point ID (PointIdFactory.id(chunkId))
                   vectors: [float x 768],          ← 768-dim float vector from Gemini Embedding 2
                   payload: {
                     "documentId": 123,             ← Foreign key to parent Document
                     "documentChunkId": 456,        ← ID of DocumentChunk
                     "pageNumber": 1,               ← Source page for citations
                     "chunkIndex": 0,               ← Sequential chunk index within document
                     "ownerId": 1                   ← Document owner ID (for future security filtering)
                   }
                 }
```

### 2. Configuration Parameters
In `application.yml`:
```yaml
qdrant:
  host: ${QDRANT_HOST:localhost}
  port: ${QDRANT_PORT:6333}           # REST API
  grpc-port: ${QDRANT_GRPC_PORT:6334} # High-performance gRPC API
  collection-name: ${QDRANT_COLLECTION:document_chunks}
  vector-dimensions: ${QDRANT_VECTOR_DIMENSIONS:768}
  use-tls: ${QDRANT_USE_TLS:false}
  api-key: ${QDRANT_API_KEY:}
```

### 3. Collection Management & Idempotency
- **Creation:** On startup (`@PostConstruct`) and on demand, `QdrantVectorStoreService.ensureCollectionExists()` checks if the collection exists. If missing, it creates the collection with **768 dimensions** and **Cosine distance**.
- **Compatibility Verification:** If the collection already exists, the service queries its parameters. If dimensions or distance metrics do not match (e.g. 512 vs 768 or Euclid vs Cosine), it throws an `IllegalStateException` preventing corrupted index queries.
- **Deterministic Point IDs:** Points use `PointIdFactory.id(chunkId)` ensuring that re-upserting a chunk simply updates the point in place without creating duplicate points.
- **Reprocessing & Deletion:**
  - When a document is reprocessed, old points are purged via `deleteVectorsByDocumentId(documentId)` using a payload filter condition `ConditionFactory.match("documentId", documentId)`.
  - When a document is deleted via `DELETE /api/documents/{id}`, its points are deleted from Qdrant prior to database entity removal.

### 4. Running the Complete Infrastructure Locally
To launch PostgreSQL, Kafka, and Qdrant together:
```powershell
# 1. Start all infrastructure services
docker compose -f infrastructure/docker-compose.yml up -d

# 2. Verify containers are healthy
docker compose -f infrastructure/docker-compose.yml ps

# 3. Check Qdrant readiness (REST: 6333, gRPC: 6334)
curl http://localhost:6333/readyz

# 4. Start Spring Boot backend
cd backend
.\mvnw.cmd spring-boot:run
```

### 5. Troubleshooting Qdrant Connections
- **`Connection refused` / `io.grpc.StatusRuntimeException: UNAVAILABLE`**: Ensure the Qdrant container is running:
  `docker compose -f infrastructure/docker-compose.yml ps`
- **Check Qdrant Logs**:
  `docker compose -f infrastructure/docker-compose.yml logs -f qdrant`
- **Verify Web UI**: Visit `http://localhost:6333/dashboard` in a web browser to inspect collections and vector counts visually.


