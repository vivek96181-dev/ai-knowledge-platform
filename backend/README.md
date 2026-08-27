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
- Stateless authentication filter (`JwtAuthenticationFilter`) reading token claims directly (zero DB lookups per auth check)
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
- Comprehensive automated test suite (42 total tests passing)

---

## 1. Prerequisites

- **Java Development Kit (JDK):** Version 21 or higher
  ```bash
  java -version
  ```
- **PostgreSQL:** Version 15 or higher (running locally or via Docker)

---

## 2. PostgreSQL Setup

### A. Local PostgreSQL (pgAdmin / psql)
Connect to PostgreSQL and create the database:
```sql
CREATE DATABASE ai_knowledge_db;
```

### B. Alternative: Docker Run Command
If you prefer running PostgreSQL via Docker:
```bash
docker run --name ai-knowledge-postgres \
  -e POSTGRES_DB=ai_knowledge_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  -d postgres:16-alpine
```

---

## 3. Environment Variables

The application follows 12-Factor methodology and reads all configuration from environment variables.
Safe defaults are provided for local development — **never commit real credentials or secrets**.

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

### Setting Environment Variables

**Windows (PowerShell):**
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="your_secure_password"
$env:JWT_SECRET="your_strong_random_secret_key_minimum_32_chars"
$env:FILE_UPLOAD_DIR="uploads"
```

**Linux / macOS:**
```bash
export DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
export DB_USERNAME="postgres"
export DB_PASSWORD="your_secure_password"
export JWT_SECRET="your_strong_random_secret_key_minimum_32_chars"
export FILE_UPLOAD_DIR="uploads"
```

---

## 4. Build and Run the Application

Navigate to the `backend` directory:
```bash
cd backend
```

### Run Tests (uses H2 — no PostgreSQL required):
**Windows:**
```powershell
.\mvnw.cmd test
```

**Linux / macOS:**
```bash
./mvnw test
```

### Start Development Server (requires PostgreSQL):
**Windows:**
```powershell
.\mvnw.cmd spring-boot:run
```

**Linux / macOS:**
```bash
./mvnw spring-boot:run
```

The application starts on `http://localhost:8080`.

---

## 5. API Endpoints & Access Matrix

| Endpoint | Method | Required Role / Auth | Description | Success Status |
| :--- | :--- | :--- | :--- | :--- |
| `/api/health` | `GET` | **Public** | Returns service health status | `200 OK` |
| `/api/auth/login` | `POST` | **Public** | Authenticate user & get JWT token | `200 OK` |
| `/api/users` | `POST` | **Public** | Register a new user account | `201 Created` |
| `/api/auth/me` | `GET` | `USER` or `ADMIN` | Get profile of currently authenticated user | `200 OK` |
| `/api/users/{id}` | `GET` | `USER` or `ADMIN` | Get user by ID | `200 OK` |
| `/api/users` | `GET` | `ADMIN` only | Get all registered users | `200 OK` |
| `/api/users/{id}` | `DELETE` | `ADMIN` only | Delete user by ID | `200 OK` |
| `/api/documents` | `POST` | `USER` or `ADMIN` | Upload a PDF document (`multipart/form-data`) | `201 Created` |
| `/api/documents` | `GET` | `USER` or `ADMIN` | List documents (`USER` sees own; `ADMIN` sees all) | `200 OK` |
| `/api/documents/{id}` | `GET` | `USER` or `ADMIN` | Get document metadata (Owner or Admin) | `200 OK` |
| `/api/documents/{id}` | `DELETE` | `USER` or `ADMIN` | Delete document & file (Owner or Admin) | `200 OK` |

### HTTP Status Codes

| Code | Meaning | Scenario |
| :--- | :--- | :--- |
| `200 OK` | Success | Read, delete, or successful login |
| `201 Created` | Created | User registered or document uploaded successfully |
| `400 Bad Request` | Validation Error | Missing/empty file, non-PDF file type, or invalid filename |
| `401 Unauthorized` | Auth Required / Bad Credentials | Missing/expired/invalid JWT, or invalid login credentials |
| `403 Forbidden` | Access Denied | Authenticated user lacks required role or tries to access another user's document |
| `404 Not Found` | Resource Not Found | User or document with given ID does not exist |
| `409 Conflict` | Duplicate Resource | Email already registered |
| `413 Payload Too Large` | Limit Exceeded | File size exceeds maximum configured limit (10MB) |

---

## 6. Example Requests & Usage Flow

### 1. Register & Login
```bash
# Register
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Vivek","email":"vivek@example.com","password":"MySecurePassword123"}'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"vivek@example.com","password":"MySecurePassword123"}'
```

### 2. Upload a PDF Document
```bash
curl -X POST http://localhost:8080/api/documents \
  -H "Authorization: Bearer <YOUR_JWT_TOKEN>" \
  -F "file=@/path/to/sample.pdf"
```

**Response (201 Created):**
```json
{
  "id": 1,
  "originalFilename": "sample.pdf",
  "contentType": "application/pdf",
  "fileSize": 1048576,
  "status": "UPLOADED",
  "createdAt": "2026-08-27T14:30:00",
  "updatedAt": "2026-08-27T14:30:00",
  "ownerId": 1,
  "ownerEmail": "vivek@example.com"
}
```

### 3. List Own Documents
```bash
curl http://localhost:8080/api/documents \
  -H "Authorization: Bearer <YOUR_JWT_TOKEN>"
```

### 4. Delete Own Document
```bash
curl -X DELETE http://localhost:8080/api/documents/1 \
  -H "Authorization: Bearer <YOUR_JWT_TOKEN>"
```

---

## 7. Architecture & Package Structure

### Request Flow for Document Management

```
HTTP Request (multipart/form-data + Bearer JWT)
    ↓
JwtAuthenticationFilter (Validates JWT, sets principal in SecurityContext)
    ↓
SecurityConfig (Checks authentication matchers for /api/documents/**)
    ↓
DocumentController (Parses request, passes user email & role to DocumentService)
    ↓
DocumentService (Validates PDF format, enforces ownership server-side)
    ├── LocalFileStorageService (Writes physical file to disk under uploads/)
    └── DocumentRepository (Persists metadata row to PostgreSQL)
```

### Package Structure

```
com.enterprise.aiknowledge
├── AiKnowledgePlatformApplication.java
│
├── config
│   ├── SecurityConfig.java              ← Security matchers & filter chain
│   └── WebMvcConfig.java
│
├── controller
│   ├── AuthController.java
│   ├── DocumentController.java          ← POST, GET, DELETE /api/documents
│   ├── HealthController.java
│   └── UserController.java
│
├── dto
│   ├── CreateUserRequest.java
│   ├── DocumentResponse.java            ← Document metadata DTO (no disk paths)
│   ├── HealthResponse.java
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   └── UserResponse.java
│
├── exception
│   ├── EmailAlreadyExistsException.java
│   ├── ErrorResponse.java
│   ├── GlobalExceptionHandler.java     ← Maps InvalidFileException(400), AccessDeniedException(403), MaxUploadSizeExceededException(413)
│   ├── InvalidFileException.java       ← Bad file upload error
│   └── ResourceNotFoundException.java
│
├── model
│   ├── Document.java                    ← JPA Entity ("documents" table)
│   ├── DocumentStatus.java              ← Enum: UPLOADED | PROCESSING | COMPLETED | FAILED
│   ├── Role.java
│   └── User.java
│
├── repository
│   ├── DocumentRepository.java          ← findByOwnerId, findByIdAndOwnerId
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
    ├── DocumentService.java             ← Upload, List, Get, Delete business logic
    ├── FileStorageService.java          ← File storage abstraction interface
    ├── LocalFileStorageService.java     ← Physical local disk storage implementation
    ├── PasswordHashingService.java
    └── UserService.java
```

---

## 8. Security & Storage Highlights

- **Server-Side Ownership Enforcement:** Client-supplied user IDs are ignored. Ownership is determined strictly from the authenticated JWT principal.
- **Path Traversal Protection:** Generated stored filenames combine UUID with normalized filenames, preventing path traversal attacks.
- **Physical Clean-up:** Deleting a document metadata record automatically removes the stored physical file from disk.
- **Storage Decoupling:** `FileStorageService` interface isolates physical storage from business logic, allowing easy transition to S3 or cloud storage in future phases without altering `DocumentService`.
