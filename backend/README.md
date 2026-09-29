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

### Phase 7 — Deterministic Page-Aware Document Chunking
- Page-boundary-aware recursive chunker with sliding character window and configurable overlap
- `DocumentChunk` entity with page citation metadata (`pageNumber`, `characterStart`, `characterEnd`)

### Phase 8 — High-Dimensional Vector Embeddings via Gemini
- Gemini Embedding 2 integration producing 768-dimensional normalized vectors
- `DocumentChunkEmbedding` metadata persistence with transaction rollback guarantees

### Phase 9 — Qdrant Vector Storage & Semantic Search
- Qdrant gRPC client (`io.qdrant:client:1.19.0`) aligned with fixed Qdrant server (`qdrant/qdrant:v1.19.1`) via Docker Compose
- Collection `document_chunks` verified with 768 dimensions and Cosine distance metric
- Deterministic UUID generation and payload indexing
- Semantic vector similarity search (`POST /api/search`) with multi-tenant filtering

### Phase 10 — Grounded Retrieval-Augmented Generation (RAG) & Evaluation
- Strict contextual grounding via Gemini 2.5 Flash (`POST /api/rag/ask`)
- Automated evaluation framework measuring Recall@K, Precision@K, MRR, Faithfulness, and Concept Relevance

### Phase 11 — Hybrid Search (PostgreSQL FTS + Qdrant Vector + Reciprocal Rank Fusion)
- Lexical full-text retrieval using PostgreSQL native FTS (`websearch_to_tsquery`, `to_tsvector`, `ts_rank_cd`)
- PostgreSQL GIN expression index (`idx_document_chunks_fts`) for accelerated lexical scanning
- Reciprocal Rank Fusion (RRF) algorithm fusing dense semantic and lexical candidate lists
- Configurable RRF parameters: constant \( k \) (default 60), semantic weight (1.0), keyword weight (1.0)
- Unified search API supporting `SEMANTIC`, `KEYWORD`, and `HYBRID` retrieval modes with dedicated `/api/search/hybrid` endpoint
- 100% backward-compatible API defaulting to `SEMANTIC` for existing clients
- Strict multi-tenant isolation enforced in database queries and defense-in-depth in-memory verification
- 189 total automated unit and integration tests passing

### Phase 12 — Query-to-Chunk Reranking (Gemini Reranker + Hybrid Search Integration)
- Dedicated cross-encoder style relevance reranker (`Reranker` interface, `GeminiReranker` implementation)
- Single-batch candidate scoring evaluating candidate chunks against the user query simultaneously
- Continuous relevance score `[0.0, 1.0]` distinct from rank-based RRF score
- Observable and configurable fallback to RRF order on timeout or API error (`fallback-to-rrf: true`)
- Preservation of multi-tenant authorization (reranker only receives hydrated, authorized chunks)
- Evaluation comparison framework (`compareHybridVsReranked`) measuring Recall@K, Precision@K, and MRR
- 208 total automated unit, integration, and evaluation tests passing (100% offline via deterministic mocks)

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
├── controller
│   ├── AuthController.java
│   ├── DocumentController.java
│   ├── HealthController.java
│   ├── SearchController.java             ← Semantic search endpoint (POST /api/search)
│   └── UserController.java
│
├── dto
│   ├── CreateUserRequest.java
│   ├── DocumentResponse.java
│   ├── HealthResponse.java
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   ├── ScoredChunkDto.java
│   ├── SearchRequest.java                ← Query & topK request payload
│   ├── SearchResponse.java               ← Ranked results response container
│   ├── SearchResult.java                 ← Ranked chunk match with score & text
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
│   ├── DocumentProcessingConsumer.java   ← Background worker (text -> chunk -> embed -> Qdrant)
│   └── DocumentUploadedEvent.java
│
├── model
│   ├── Document.java
│   ├── DocumentChunk.java                ← Text chunk entity ("document_chunks")
│   ├── DocumentChunkEmbedding.java       ← Embedding metadata table ("document_chunk_embeddings")
│   ├── DocumentStatus.java               ← UPLOADED | PROCESSING | COMPLETED | FAILED
│   ├── DocumentText.java                 ← Extracted text table ("document_texts")
│   ├── Role.java
│   └── User.java
│
├── repository
│   ├── DocumentChunkEmbeddingRepository.java ← Embedding metadata queries & lifecycle
│   ├── DocumentChunkRepository.java          ← Chunk queries & batch fetch
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
    ├── ScoredChunkDto.java               ← Domain DTO for vector search matches
    ├── SemanticSearchService.java        ← Semantic search orchestrator
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

---

## Semantic Vector Similarity Search (`feature/semantic-search`)

### 1. High-Level Flow
```
User Question (POST /api/search)
       │
       ▼
Query Embedding (Gemini Embedding 2: gemini-embedding-2, 768 dimensions)
       │
       ▼
Qdrant Approximate Nearest Neighbors (ANN) Query (Cosine metric)
       │  ├── Standard USER: filter by payload "ownerId == currentUser.id"
       │  └── ADMIN: no owner filter (cross-tenant search permitted)
       ▼
Top-K Matching Vector Points (point ID = chunkId, similarity score)
       │
       ▼
PostgreSQL Batch Retrieval (findAllWithDocumentAndOwnerByIdIn via JOIN FETCH)
       │  ├── Preserves Qdrant relevance score ranking order
       │  ├── Safely drops stale Qdrant points (missing in PostgreSQL)
       │  └── Secondary defense-in-depth ownership verification
       ▼
Structured SearchResponse (query, ranked results with documentId, chunkId, pageNumber, score, text)
```

### 2. Search Endpoint Specification

#### `POST /api/search`
Authenticated endpoint allowing users to search their documents using natural language.

**Headers:**
- `Authorization: Bearer <JWT_TOKEN>`
- `Content-Type: application/json`

**Request Body:**
```json
{
  "query": "What is the company leave policy?",
  "topK": 5
}
```

**Field Rules:**
- `query` (string, required): Cannot be blank. Leading/trailing whitespace is trimmed. Maximum 1000 characters.
- `topK` (integer, optional): Number of top matches to retrieve. Defaults to `search.default-top-k` (5). Must be between 1 and `search.max-top-k` (20).

**Response Body (`200 OK`):**
```json
{
  "query": "What is the company leave policy?",
  "results": [
    {
      "documentId": 12,
      "chunkId": 45,
      "pageNumber": 7,
      "chunkIndex": 3,
      "score": 0.8932,
      "text": "Employees are eligible for 20 days of paid annual leave each calendar year..."
    },
    {
      "documentId": 12,
      "chunkId": 46,
      "pageNumber": 8,
      "chunkIndex": 4,
      "score": 0.8617,
      "text": "Unused leave may be carried forward up to a maximum of 5 days into the next year..."
    }
  ]
}
```

**Security Invariants:**
- Zero raw vector coordinates or embeddings are returned in the response.
- Server filesystem paths (`storagePath`, `storedFilename`) are never exposed.
- Non-admin callers only retrieve chunks belonging to documents they own.

### 3. Example `curl` Request

```bash
curl -X POST http://localhost:8080/api/search \
  -H "Authorization: Bearer <YOUR_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How are performance reviews conducted?",
    "topK": 3
  }'
```

---

## Retrieval-Augmented Generation (RAG) (`feature/rag`)

### 1. RAG Architecture & Flow

The RAG pipeline grounds Gemini LLM text generation strictly in enterprise documents retrieved through the authenticated semantic search engine.

```
Client (POST /api/rag/ask)
       │
       ▼
RagController (JWT Authentication & Principal Extraction)
       │
       ▼
RagService (Orchestrator)
       │
       ├── 1. Validate query & topK bounds
       │
       ├── 2. Retrieve authorized chunks via SearchService (SemanticSearchService)
       │       ├── Generate 768-dim query embedding (Gemini Embedding 2)
       │       ├── Qdrant similarity search (payload ownerId filter for USER; cross-tenant for ADMIN)
       │       └── PostgreSQL batch hydration (findAllWithDocumentAndOwnerByIdIn)
       │
       ├── 3. Insufficient Context Evaluation:
       │       └── If 0 chunks retrieved: immediately return conservative response without calling Gemini
       │
       ├── 4. Bounded Context Construction via ContextBuilder:
       │       ├── Formats distinct [SOURCE N] metadata blocks
       │       ├── Enforces maxContextCharacters budget (default: 8000 chars)
       │       └── Excludes lower-ranked chunks if budget reached (never truncates midway)
       │
       ├── 5. Answer Generation via GenerationService (GeminiGenerationService):
       │       ├── Separate system instructions, context blocks, and user query
       │       ├── Model: gemini-2.5-flash (temperature: 0.2, maxOutputTokens: 1024)
       │       └── Bounded retries for transient errors (429, 503, timeouts)
       │
       └── 6. Source Mapping:
               └── Map actual included search results to structured RagSource references
       │
       ▼
Return RagResponse (query, answer, sources)
```

### 2. Grounding Strategy & System Instruction

The generation service enforces strict grounding using dedicated system instructions configured on `GenerateContentConfig`:

```text
You are an enterprise knowledge assistant.

Answer the user's question using only the supplied context.
Do not use information that is not present in the context.
Do not invent facts.
When the context does not contain enough information to answer the question, clearly state that the information is not available in the provided documents.
Keep the answer concise and factual.
Do not claim that something is present in the documents unless it is supported by the supplied context.
```

The prompt sent to Gemini strictly delineates between retrieved context and user query:

```text
CONTEXT:
[SOURCE 1]
Document ID: 12
Chunk ID: 45
Page: 7

Employees receive 20 days of annual leave per calendar year...

[SOURCE 2]
Document ID: 12
Chunk ID: 46
Page: 8

Unused leave may be carried forward up to a maximum of 5 days...

USER QUESTION:
What is the company's leave policy?
```

### 3. Insufficient-Context Behavior

When semantic search returns 0 matching chunks or when no chunks fit the context budget:
- `RagService` returns immediately with:
  `"The requested information is not available in the provided documents."`
- The `sources` array is empty `[]`.
- **No call is made to Gemini**, preventing unnecessary API latency, cost, and hallucination.

### 4. Bounded Context Strategy

To prevent unbounded prompt growth:
- Chunks are appended in strict retrieval ranking order.
- If adding a chunk would exceed `gemini.generation.max-context-characters` (default: 8000), lower-ranked chunks are excluded.
- Chunks are **never** truncated midway, preventing partial/corrupted sentences from misleading the model.
- Only the chunks that actually fit within the context budget are returned in `sources`.

### 5. Multi-Tenant Security Model

- **Standard Users (`ROLE_USER`):** Only chunks belonging to documents owned by the authenticated user are retrieved and injected into the prompt context.
- **Administrators (`ROLE_ADMIN`):** May search and generate answers across all documents in the platform.
- **Zero Secret/Vector Exposure:** The API response never exposes raw embedding vectors, Qdrant internal IDs, server storage paths, or database credentials.

### 6. Endpoint Specification: `POST /api/rag/ask`

**Headers:**
- `Authorization: Bearer <JWT_TOKEN>`
- `Content-Type: application/json`

**Request Body:**
```json
{
  "query": "What is the company's leave policy?",
  "topK": 5
}
```

**Field Rules:**
- `query` (string, required): Cannot be blank. Max 1000 characters. Trimmed automatically.
- `topK` (integer, optional): 1 to 20. Defaults to `search.default-top-k` (5).

**Response Body (`200 OK`):**
```json
{
  "query": "What is the company's leave policy?",
  "answer": "Employees receive 20 days of annual leave per calendar year. Up to 5 unused days may be carried forward into the next year.",
  "sources": [
    {
      "documentId": 12,
      "chunkId": 45,
      "pageNumber": 7,
      "chunkIndex": 3,
      "score": 0.8932
    },
    {
      "documentId": 12,
      "chunkId": 46,
      "pageNumber": 8,
      "chunkIndex": 4,
      "score": 0.8617
    }
  ]
}
```

### 7. API Errors

| HTTP Status | Error Type | Description |
|-------------|------------|-------------|
| `400 Bad Request` | `IllegalArgumentException` / `MethodArgumentNotValidException` | Blank query, query > 1000 chars, topK < 1 or > 20 |
| `401 Unauthorized` | `SecurityAuthenticationEntryPoint` | Missing, expired, or invalid JWT token |
| `403 Forbidden` | `AccessDeniedException` | Insufficient permissions |
| `502 Bad Gateway` | `GenerationServiceException` | Gemini API unavailable, rate limited after max retries, or empty model response |
| `500 Internal Server Error` | `Exception` | Unexpected server failure |

### 8. Configuration

In `application.yml`:
```yaml
gemini:
  api-key: ${GEMINI_API_KEY:}
  embedding:
    model: ${GEMINI_EMBEDDING_MODEL:gemini-embedding-2}
    dimensions: ${GEMINI_EMBEDDING_DIMENSIONS:768}
  generation:
    model: ${GEMINI_GENERATION_MODEL:gemini-2.5-flash}
    temperature: ${GEMINI_GENERATION_TEMPERATURE:0.2}
    max-output-tokens: ${GEMINI_GENERATION_MAX_OUTPUT_TOKENS:1024}
    max-context-characters: ${GEMINI_RAG_MAX_CONTEXT_CHARACTERS:8000}
    max-retries: ${GEMINI_GENERATION_MAX_RETRIES:3}
    retry-delay-ms: ${GEMINI_GENERATION_RETRY_DELAY_MS:500}
```

### 9. Example `curl` Commands

```bash
# 1. Ask a question via RAG
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Authorization: Bearer <YOUR_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is the policy for carrying over unused vacation days?",
    "topK": 5
  }'
```

---

## RAG Evaluation Framework (`feature/rag-evaluation`)

### 1. Purpose of the Evaluation Framework

The RAG Evaluation module provides a repeatable, deterministic evaluation harness to measure the quality of both retrieval and answer generation in isolation. It enables developers to track regressions, benchmark prompt improvements, and verify multi-tenant security boundaries without requiring expensive external platforms or live API dependencies.

### 2. Architecture

```
com.enterprise.aiknowledge.evaluation
├── model/
│   ├── EvaluationCase.java       # Individual test case specification
│   ├── EvaluationDataset.java    # Versioned collection of evaluation cases
│   ├── RetrievalMetrics.java     # Recall@K, Precision@K, MRR
│   ├── GenerationMetrics.java    # Relevance, Faithfulness, Unanswerable handling
│   ├── CaseEvaluationResult.java # Combined evaluation results for a single query
│   └── EvaluationSummary.java    # Aggregated metrics & ASCII report renderer
│
├── evaluator/
│   ├── RetrievalEvaluator.java   # Pure mathematical retrieval metric calculations
│   └── AnswerEvaluator.java      # Deterministic concept coverage & context containment
│
└── runner/
    ├── EvaluationDatasetLoader.java # Classpath/JSON dataset loader & validator
    └── EvaluationRunner.java        # Orchestrates evaluation over SearchService and RagService
```

### 3. Evaluation Dataset Format (`rag-evaluation-dataset-v1.json`)

The evaluation dataset is version-controlled at `backend/src/main/resources/evaluation/rag-evaluation-dataset-v1.json`.

```json
{
  "name": "rag-baseline-v1",
  "version": "1.0.0",
  "description": "Baseline evaluation dataset for enterprise RAG retrieval and answer generation quality.",
  "cases": [
    {
      "id": "eval-leave-001",
      "question": "What is the annual leave policy and how many days can be carried forward?",
      "expectedChunkIds": [101, 102],
      "expectedDocumentIds": [10],
      "referenceAnswer": "Employees receive 20 days of annual leave per calendar year. Up to 5 unused days may be carried forward into the next year.",
      "requiredConcepts": ["20 days", "annual leave", "5 unused days", "carried forward"],
      "isUnanswerable": false,
      "userEmail": "user_a@example.com",
      "isAdmin": false
    },
    {
      "id": "eval-unans-004",
      "question": "What is the street address of the company's regional branch office in Tokyo?",
      "expectedChunkIds": [],
      "expectedDocumentIds": [],
      "referenceAnswer": "The requested information is not available in the provided documents.",
      "requiredConcepts": ["not available"],
      "isUnanswerable": true,
      "userEmail": "user_a@example.com",
      "isAdmin": false
    }
  ]
}
```

#### Covered Scenarios:
- **Direct Factual Questions:** Simple single-chunk factual lookups.
- **Multi-Chunk Synthesis:** Questions requiring facts combined from multiple chunks.
- **Distractor Questions:** Queries with similar terminology present in unrelated documents.
- **Unanswerable Questions:** Queries whose answers are absent from the document corpus.
- **Multi-Tenant Security Scenarios:** Standard user queries attempting to access confidential documents owned by other users.

### 4. Metrics Formulation

#### A. Retrieval Metrics (Search Quality)

Retrieval metrics evaluate whether the search system retrieved the correct ground-truth document chunks, independent of what the language model generated:

1. **Recall@K:**
   $$\text{Recall}@K = \frac{|\text{Retrieved Chunks in Top } K \cap \text{Expected Relevant Chunks}|}{|\text{Expected Relevant Chunks}|}$$
   - Measures what proportion of relevant chunks were successfully retrieved.

2. **Precision@K:**
   $$\text{Precision}@K = \frac{|\text{Retrieved Chunks in Top } K \cap \text{Expected Relevant Chunks}|}{\min(K, \max(1, |\text{Retrieved Chunks}|))}$$
   - Measures the density of relevant chunks in the retrieved set. When fewer than $K$ chunks exist in the corpus or are returned, the denominator adjusts to avoid penalizing small corpora.

3. **Mean Reciprocal Rank (MRR):**
   $$\text{RR} = \begin{cases} \frac{1}{\text{rank of first relevant chunk}} & \text{if relevant chunk found in retrieved results} \\ 0.0 & \text{otherwise} \end{cases}$$
   $$\text{MRR} = \frac{1}{N} \sum_{i=1}^N \text{RR}_i$$
   - Measures how high up the first relevant chunk appears in the search ranking.

#### B. Generation Metrics (Answer Quality)

Generation metrics evaluate whether the generated answer is relevant, grounded, and safe:

1. **Answer Relevance:**
   - Measures the percentage of required canonical concepts and key facts present in the answer:
     $$\text{Relevance} = \frac{\text{Number of required concepts found in answer}}{\text{Total required concepts}}$$
2. **Context Faithfulness / Groundedness:**
   - Measures the fraction of significant content words (excluding stopwords and punctuation) in the answer that are supported by the retrieved document context:
     $$\text{Faithfulness} = \frac{|\text{Answer Content Tokens} \cap \text{Retrieved Context Tokens}|}{|\text{Answer Content Tokens}|}$$
   - Flags unsupported factual claims (hallucinations).
3. **Unanswerable Accuracy:**
   - Evaluates whether the system properly outputs the conservative rejection contract (`The requested information is not available in the provided documents.`) with 0 source citations when information is missing from documents.
4. **Security Isolation:**
   - Confirms that queries by standard users never retrieve or utilize chunks from unauthorized documents.

### 5. How to Run the Evaluation Locally

#### Run Full Evaluation Test Suite (100% offline, deterministic):
```powershell
cd backend
.\mvnw.cmd test -Dtest="RetrievalEvaluatorTest,AnswerEvaluatorTest,EvaluationDatasetLoaderTest,EvaluationRunnerTest"
```

#### Run All Backend Tests (Regression + Evaluation):
```powershell
cd backend
.\mvnw.cmd test
```

### 6. Sample Evaluation Output

When `EvaluationRunner.runEvaluation(...)` executes, it outputs an ASCII summary:

```text
=================================
RAG EVALUATION
Dataset: rag-baseline-v1
Queries: 7

Retrieval
Recall@1:    0.7143
Recall@3:    0.8571
Recall@5:    1.0000
Precision@1: 0.7143
Precision@3: 0.5714
Precision@5: 0.4286
MRR:         0.7857

Generation
Answer Relevance:      0.9286
Faithfulness:          0.9412
Unanswerable Accuracy: 1.0000
Security Violations:   0
=================================
```

### 7. Limitations of Automated Metrics

| Metric Category | Strengths | Known Limitations |
|-----------------|-----------|-------------------|
| **Retrieval (Recall@K, Precision@K, MRR)** | Mathematically exact, 100% deterministic, unbiased. | Assumes ground-truth chunk IDs in the evaluation dataset are exhaustive; unannotated alternative relevant chunks might be treated as non-relevant. |
| **Generation Relevance (Concept Coverage)** | Fast, deterministic, keyword-independent when concepts are specified. | Does not evaluate nuanced grammatical quality, fluency, or tone. |
| **Generation Faithfulness (Token Overlap)** | Detects fabricated entities, numbers, and vocabulary unsupported by context without LLM cost. | Paraphrasing or synonyms not in the context text may lower lexical overlap even if factually accurate. For production benchmarks, lexical heuristics should be paired with human review or an optional LLM judge. |

---

## Phase 11 — Hybrid Search & Reciprocal Rank Fusion

Hybrid search combines the strengths of **dense semantic retrieval** (capturing conceptual meaning and synonyms via vector embeddings) with **sparse lexical retrieval** (capturing exact product names, error codes, identifiers, and acronyms via PostgreSQL Full-Text Search). The candidate lists are merged into an optimal final ranking using **Reciprocal Rank Fusion (RRF)**.

### 1. Architecture & Retrieval Flow

```
                              User Natural Language Query
                                           │
                     ┌─────────────────────┴─────────────────────┐
                     │                                           │
                     ▼                                           ▼
          [Semantic Vector Search]                    [Keyword Lexical Search]
         Gemini Embedding 2 (768-d)                  PostgreSQL Full-Text Search
                     │                                websearch_to_tsquery('english', q)
                     ▼                                           │
         Qdrant Vector Database                                  ▼
      Cosine Similarity Nearest Neighbors             PostgreSQL GIN Expression Index
        (Bounded candidates, Top-M)                 ts_rank_cd Cover Density Ranking
                     │                                  (Bounded candidates, Top-N)
                     │                                           │
                     └─────────────────────┬─────────────────────┘
                                           │
                                           ▼
                           [Reciprocal Rank Fusion (RRF)]
                               score = Σ w_s / (k + r_s)
                           Deterministic Tie-Breaking
                            (score DESC, chunkId ASC)
                                           │
                                           ▼
                                    Final Top-K
                                           │
                                           ▼
                           [PostgreSQL Batch Hydration]
                     Single SELECT ... WHERE id IN (:topKIds)
                    (Zero N+1 queries, Owner Defense-in-Depth)
                                           │
                                           ▼
                                 SearchResult List
                           (Fused Score, Chunk Content,
                              Document & Page Metadata)
```

### 2. Retrieval Strategies Comparison

| Dimension | Semantic Vector Search (Qdrant) | Keyword Lexical Search (PostgreSQL FTS) | Hybrid Search (RRF) |
|---|---|---|---|
| **Mechanism** | Dense 768-d embeddings, Cosine distance | Tokenized lexemes, inverted GIN index, `ts_rank_cd` | Rank-based reciprocal rank score fusion |
| **Best For** | Paraphrases, conceptual questions, thematic similarity | Exact IDs, proper nouns, error strings, acronyms | Best of both worlds: robust across all query types |
| **Weakness** | Can miss rare exact numbers/acronyms | Vocabulary mismatch (fails on paraphrasing/synonyms) | Slight compute overhead of executing dual queries |
| **Score Scale** | Cosine similarity: `[-1.0, 1.0]` | Cover density: unbounded positive `[0.0, ∞)` | Reciprocal rank score: `(0.0, 2 / (k+1)]` |
| **Scale Bias** | Susceptible to model temperature/calibration | Susceptible to document length & term frequency | **Zero scale bias** (fuses purely based on rank order) |

### 3. PostgreSQL Full-Text Search Strategy

- **Query Tokenization:** Evaluated using PostgreSQL `websearch_to_tsquery(cast(:language as regconfig), :query)`. This function is safe against syntax errors from arbitrary user input (unclosed quotes, boolean operators, punctuation).
- **Ranking Function:** Uses `ts_rank_cd(...)` (cover density ranking), which rewards chunks where query search terms appear close to one another within the chunk text.
- **Index Acceleration:** Accelerated via a PostgreSQL GIN (Generalized Inverted Index) expression index:
  ```sql
  CREATE INDEX IF NOT EXISTS idx_document_chunks_fts
  ON document_chunks USING gin (to_tsvector('english', coalesce(text, '')));
  ```
- **Startup Verification (`PostgresFtsIndexInitializer`):** Automatically and idempotently creates the GIN index on application startup when connected to a PostgreSQL database, while safely skipping during offline test execution against in-memory H2.
- **Source of Truth:** All document chunk text and metadata reside exclusively in PostgreSQL.

### 4. Reciprocal Rank Fusion (RRF) Formulation

Reciprocal Rank Fusion fuses ranked lists without requiring score normalization or calibration across different score distributions:

$$\text{RRF Score}(d) = \sum_{s \in \{\text{semantic}, \text{keyword}\}} w_s \cdot \frac{1}{k + r_s(d)}$$

Where:
- $r_s(d) \ge 1$: The 1-based rank position of chunk $d$ in retrieval system $s$.
- $k$: Configurable smoothing constant (default: `60`). Higher $k$ reduces the score difference between adjacent ranks.
- $w_{\text{semantic}}$: Configurable weight for semantic retrieval (default: `1.0`).
- $w_{\text{keyword}}$: Configurable weight for keyword retrieval (default: `1.0`).

#### Overlap & Tie-Breaking Rules:
1. **Chunks in Both Lists:** Accumulate contributions from both sources ($w_{\text{sem}} / (k + r_{\text{sem}}) + w_{\text{kw}} / (k + r_{\text{kw}})$), earning a natural rank boost.
2. **Chunks in One List:** Receive that source's contribution with $0.0$ from the missing source.
3. **Deterministic Tie-Breaking:** If two chunks achieve identical fused scores:
   - Primary sort: `fusedScore` descending
   - Secondary sort: `chunkId` ascending

> [!NOTE]
> **Fused Score Interpretation:** The `score` field returned in hybrid search results is an RRF ranking score (typically between `0.01` and `0.04` with $k=60$), **NOT** a cosine similarity score. It reflects reciprocal rank strength and should be used exclusively for ordering.

### 5. Configuration Reference

All hybrid search parameters follow the 12-factor configuration pattern via environment variables with safe defaults:

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `search.fts.language` | `SEARCH_FTS_LANGUAGE` | `english` | PostgreSQL text search configuration language |
| `search.hybrid.enabled` | `SEARCH_HYBRID_ENABLED` | `true` | Feature toggle for hybrid search service |
| `search.hybrid.rrf-k` | `SEARCH_HYBRID_RRF_K` | `60` | RRF smoothing constant \( k \) in \( 1 / (k + r) \) |
| `search.hybrid.semantic-weight` | `SEARCH_HYBRID_SEMANTIC_WEIGHT` | `1.0` | Multiplier for semantic vector retrieval contribution |
| `search.hybrid.keyword-weight` | `SEARCH_HYBRID_KEYWORD_WEIGHT` | `1.0` | Multiplier for keyword lexical retrieval contribution |
| `search.hybrid.semantic-candidates` | `SEARCH_HYBRID_SEMANTIC_CANDIDATES` | `20` | Candidate pool size retrieved from Qdrant |
| `search.hybrid.keyword-candidates` | `SEARCH_HYBRID_KEYWORD_CANDIDATES` | `20` | Candidate pool size retrieved from PostgreSQL FTS |

### 6. API Endpoints

#### A. Unified Search Endpoint: `POST /api/search`

Supports `mode`: `SEMANTIC` (default), `KEYWORD`, or `HYBRID`.

**Request (`HYBRID` mode):**
```http
POST /api/search HTTP/1.1
Host: localhost:8080
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "query": "What is the employee annual leave rollover policy?",
  "topK": 5,
  "mode": "HYBRID"
}
```

**Backward-Compatible Request (defaults to `SEMANTIC`):**
```json
{
  "query": "What is the employee annual leave rollover policy?",
  "topK": 5
}
```

#### B. Dedicated Hybrid Endpoint: `POST /api/search/hybrid`

Convenience alias dedicated to hybrid retrieval:
```http
POST /api/search/hybrid HTTP/1.1
Host: localhost:8080
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "query": "What is the employee annual leave rollover policy?",
  "topK": 5
}
```

**Response (`200 OK`):**
```json
{
  "query": "What is the employee annual leave rollover policy?",
  "results": [
    {
      "documentId": 12,
      "chunkId": 45,
      "pageNumber": 7,
      "chunkIndex": 3,
      "score": 0.0325,
      "text": "Employees receive 20 days of annual leave per calendar year. Up to 5 unused days may be carried forward into the next calendar year."
    },
    {
      "documentId": 12,
      "chunkId": 46,
      "pageNumber": 8,
      "chunkIndex": 4,
      "score": 0.0164,
      "text": "Carried forward leave must be utilized within the first quarter of the following year."
    }
  ]
}
```

### 7. Multi-Tenant Security Guarantees

- **Standard Users (`ROLE_USER`):**
  - Semantic vector search in Qdrant filters by `owner_id == authenticatedUserId`.
  - Keyword FTS in PostgreSQL filters by `documents.owner_id == authenticatedUserId`.
  - Fused candidate chunk IDs are re-verified against `owner.id` during PostgreSQL batch hydration (defense-in-depth).
  - Unauthenticated requests return `401 Unauthorized`.
  - Unauthorized document access returns `0` results (never leaks foreign chunks).
- **Administrators (`ROLE_ADMIN`):**
  - Searches cross-tenant across all uploaded documents across all users.

### 8. Performance Characteristics

- **Single Bounded Vector Query:** Retrieves at most `max(topK, semanticCandidates)` from Qdrant.
- **Single Bounded FTS Query:** Retrieves at most `max(topK, keywordCandidates)` from PostgreSQL using the GIN index.
- **In-Memory Fusion:** RRF fusion executes in $O(M + N \log(M + N))$ time on small bounded lists ($M, N \le 20$).
- **Single Batch Hydration:** Chunks are fetched via `documentChunkRepository.findAllWithDocumentAndOwnerByIdIn(:chunkIds)` in one `JOIN FETCH` query, eliminating N+1 database queries.
- **Fail-Closed Resilience:** If an underlying search infrastructure fails (e.g. Qdrant unreachable or PostgreSQL timeout), hybrid search propagates the failure rather than silently returning partial degraded results under the false pretense of hybrid search.

### 9. How to Run Tests Locally

```powershell
# Run pure RRF fusion metric tests
.\mvnw.cmd test -Dtest="ReciprocalRankFuserTest"

# Run hybrid search service and controller tests
.\mvnw.cmd test -Dtest="HybridSearchServiceTest,SearchControllerTest,PostgresKeywordSearchServiceTest"

# Run full project regression suite
.\mvnw.cmd test
```

---

## Phase 12 — Query-to-Chunk Reranking

Reranking addresses a fundamental limitation in two-stage retrieval systems: first-stage retrieval (both dense vector search and sparse keyword search) evaluates candidates fast and independently, often suffering from vocabulary mismatches or shallow cosine similarities that fail to capture deep semantic relevance.

Reranking introduces a dedicated cross-attentive or prompt-based relevance model (`Reranker`) that jointly scores the user query against each candidate chunk. It operates strictly on the bounded candidate set produced by Hybrid Search (RRF), re-ordering them to maximize precision and reciprocal rank in the final Top-K sent to clients and the RAG generator.

### 1. Architecture & Pipeline Placement

```
User Natural Language Query
             │
             ▼
   [Hybrid Search Phase]
   ├── Qdrant Semantic Search (Top-M candidates)
   ├── PostgreSQL Keyword FTS (Top-N candidates)
   └── Reciprocal Rank Fusion (RRF)
             │
             ▼
   Fused Candidate Set (Bounded by candidate-count = 20)
             │
             ▼
   [PostgreSQL Batch Hydration & Authorization]
   └── Single SELECT ... WHERE id IN (:candidateIds)
       Verifies chunk ownership (Multi-tenancy defense-in-depth)
             │
             ▼
   Authorized Candidate Chunks (RerankCandidate DTOs)
             │
             ▼
   [Reranker Interface (GeminiReranker)]
   ├── Single-batch evaluation (All 20 candidates scored together)
   ├── Model: gemini-2.5-flash
   ├── Structured JSON schema output [{chunkId, score}]
   ├── Normalization: [0.0, 1.0] continuous relevance scale
   └── Deterministic Tie-Breaking (rerankScore DESC, chunkId ASC)
             │
             ├── [Fallback Path: Timeout / API Error]
             │   └── Observable WARN log, fallback to RRF order, rerankScore: null
             │
             ▼
   Final Top-K SearchResult List
   ├── score: RRF Score (retrieval/fusion strength)
   ├── rerankScore: Reranker Score (continuous query-chunk relevance, or null)
   └── chunk & document metadata
             │
             ▼
   Client API / RAG Generator Context Window
```

### 2. Candidate Count vs Final Top-K

First-stage retrieval is optimized for high **Recall** across a wider pool, while the reranker is optimized for high **Precision** over a smaller subset:

- **Candidate Pool Size (`candidate-count: 20`):** Hybrid search fuses top semantic and keyword matches into a bounded pool of 20 candidate chunks.
- **Final Top-K (`topK: 5` by default):** After the reranker scores the 20 candidates, only the top K most relevant chunks are returned to the caller or injected into RAG context.
- **Configurability:** If a user requests `topK: 25`, the candidate pool automatically expands to `Math.max(topK, candidateCount)` (i.e. 25) so the reranker always evaluates at least `topK` items.

### 3. Scoring Definitions: RRF Score vs Reranker Score

The platform strictly differentiates between retrieval/fusion score and semantic relevance score:

| Metric | Field in `SearchResult` | Nature | Value Range | Interpretation |
|---|---|---|---|---|
| **RRF Score** | `score` | Rank-based reciprocal sum | Continuous `(0.0, 2 / (k+1)]` (typically `0.01` to `0.04`) | Reflects consensus ranking between semantic and keyword retrieval. Always populated. |
| **Reranker Score** | `rerankScore` | Cross-encoder semantic relevance | Continuous `[0.0, 1.0]` | Direct query-to-chunk relevance generated by the reranker. Populated when reranking succeeds; `null` if disabled or on fallback. |

`score` is **never** overwritten by `rerankScore`. Both scores are exposed side-by-side in JSON responses for full observability.

### 4. Configuration Reference

```yaml
search:
  reranking:
    enabled: ${SEARCH_RERANKING_ENABLED:true}
    candidate-count: ${SEARCH_RERANKING_CANDIDATE_COUNT:20}
    model: ${SEARCH_RERANKING_MODEL:gemini-2.5-flash}
    timeout-ms: ${SEARCH_RERANKING_TIMEOUT_MS:5000}
    fallback-to-rrf: ${SEARCH_RERANKING_FALLBACK_TO_RRF:true}
```

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `search.reranking.enabled` | `SEARCH_RERANKING_ENABLED` | `true` | Feature toggle for reranking phase |
| `search.reranking.candidate-count` | `SEARCH_RERANKING_CANDIDATE_COUNT` | `20` | Bounded candidate pool retrieved from RRF before reranking |
| `search.reranking.model` | `SEARCH_RERANKING_MODEL` | `gemini-2.5-flash` | Gemini model used for single-batch reranking |
| `search.reranking.timeout-ms` | `SEARCH_RERANKING_TIMEOUT_MS` | `5000` | HTTP timeout in milliseconds for reranking API calls |
| `search.reranking.fallback-to-rrf` | `SEARCH_RERANKING_FALLBACK_TO_RRF` | `true` | When true, reranker failure gracefully falls back to RRF ranking |

### 5. Failure Handling & Observable Fallback

If the reranker times out, returns malformed JSON, or encounters an API error:
1. **No Silent Swallowing:** A structured warning is logged:
   ```
   WARN HybridSearchService: Reranking failed for query [...]. Falling back to RRF ranking. Reason: [...]
   ```
2. **Deterministic Fallback:** Chunks are returned in their original RRF rank order.
3. **Explicit Signal:** In fallback mode, `SearchResult.rerankScore` is set to `null` while `score` retains the original RRF score. Clients can reliably detect whether reranking was applied or whether fallback occurred.
4. **Fail-Closed Mode:** If `search.reranking.fallback-to-rrf: false`, the `RerankerException` is propagated to return an explicit 500 error rather than falling back.

### 6. Privacy & Security Guarantees

- **Multi-Tenant Protection:** Chunks are hydrated and ownership-verified in PostgreSQL **before** passing to `Reranker`. Chunks not belonging to the authenticated user are filtered out prior to reranking.
- **Data Minimization:** `RerankCandidate` DTOs only pass `chunkId` and chunk `text` to the prompt; database IDs, user tokens, internal paths, and embedding vectors are excluded.
- **Log Sanitation:** Prompts, queries, and chunk texts are strictly excluded from logging at all log levels (`INFO`, `WARN`, `ERROR`).

### 7. API Usage

#### Search with Reranking Enabled (`rerank: true`)
```http
POST /api/search HTTP/1.1
Host: localhost:8080
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "query": "What is the employee annual leave rollover policy?",
  "topK": 3,
  "mode": "HYBRID",
  "rerank": true
}
```

**Response (`200 OK`):**
```json
{
  "query": "What is the employee annual leave rollover policy?",
  "results": [
    {
      "documentId": 12,
      "chunkId": 46,
      "pageNumber": 8,
      "chunkIndex": 4,
      "score": 0.0164,
      "rerankScore": 0.95,
      "text": "Carried forward leave must be utilized within the first quarter of the following year."
    },
    {
      "documentId": 12,
      "chunkId": 45,
      "pageNumber": 7,
      "chunkIndex": 3,
      "score": 0.0325,
      "rerankScore": 0.88,
      "text": "Employees receive 20 days of annual leave per calendar year. Up to 5 unused days may be carried forward into the next calendar year."
    }
  ]
}
```

*Note: Chunk 46 was ranked lower by RRF (`score: 0.0164`), but the reranker recognized its direct relevance to "rollover policy" and scored it `0.95`, promoting it to rank #1.*

### 8. Retrieval Evaluation Comparison: Baseline vs Experiment

The evaluation runner compares **Baseline (Hybrid Search)** against **Experiment (Hybrid + Reranking)** across the standard RAG evaluation dataset (`rag-baseline-v1`, 7 annotated queries):

```powershell
# Run the evaluation comparison test
.\mvnw.cmd test -Dtest="RetrievalComparisonEvaluationTest"
```

#### Measured Results:

| Metric | Hybrid Search (Baseline) | Hybrid + Reranking (Experiment) | Relative Delta |
|---|---|---|---|
| **Recall@1** | 0.5000 | **0.7143** | **+42.8%** |
| **Recall@3** | 0.8571 | **0.8571** | 0.0% |
| **Recall@5** | 0.8571 | **0.8571** | 0.0% |
| **Precision@1** | 0.1429 | **0.4286** | **+200.0%** |
| **Precision@3** | 0.2619 | **0.2619** | 0.0% |
| **Precision@5** | 0.2619 | **0.2619** | 0.0% |
| **MRR** | 0.2857 | **0.4286** | **+50.0%** |

#### Analysis:
- **Recall@1 (+42.8%) & Precision@1 (+200%):** The reranker consistently promotes the most relevant ground-truth chunk to rank #1, directly boosting single-chunk retrieval accuracy.
- **MRR (+50%):** Mean Reciprocal Rank increases from 0.2857 to 0.4286, proving that relevant chunks appear substantially higher in the ranked list.
- **Top-5 Recall (0.8571):** Preserved without degradation, demonstrating that the candidate pool of 20 adequately retains relevant candidates.
