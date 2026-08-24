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
Safe defaults are provided for local development — **never commit real credentials**.

| Variable | Description | Default Value |
| :--- | :--- | :--- |
| `SERVER_PORT` | Port the Spring Boot server listens on | `8080` |
| `DB_URL` | Full JDBC connection string to PostgreSQL | `jdbc:postgresql://localhost:5432/ai_knowledge_db` |
| `DB_USERNAME` | PostgreSQL database user | `postgres` |
| `DB_PASSWORD` | PostgreSQL database password | `postgres` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed frontend origins | `http://localhost:3000,http://localhost:5173` |

### Setting Environment Variables

**Windows (PowerShell):**
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="your_secure_password"
```

**Linux / macOS:**
```bash
export DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
export DB_USERNAME="postgres"
export DB_PASSWORD="your_secure_password"
```

> **Note:** The application uses `ddl-auto: update` — Hibernate will automatically create or
> update the `users` table on startup. No manual SQL migrations are needed for this phase.

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

## 5. API Endpoints

### Health Check

| Method | Path | Description | Success Status |
|:---|:---|:---|:---|
| `GET` | `/api/health` | Returns service health status | `200 OK` |

### User Management

| Method | Path | Description | Success Status |
|:---|:---|:---|:---|
| `POST` | `/api/users` | Create a new user | `201 Created` |
| `GET` | `/api/users` | Get all users | `200 OK` |
| `GET` | `/api/users/{id}` | Get a user by ID | `200 OK` |
| `DELETE` | `/api/users/{id}` | Delete a user by ID | `200 OK` |

### HTTP Status Codes

| Code | Meaning |
|:---|:---|
| `201 Created` | User created successfully |
| `200 OK` | Successful read or delete |
| `400 Bad Request` | Validation failure (blank field, invalid email, short password) |
| `404 Not Found` | User with the given ID does not exist |
| `409 Conflict` | Email address already registered |

---

## 6. Example Requests & Responses

### Create a User
```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Vivek",
    "email": "vivek@example.com",
    "password": "MySecurePassword123"
  }'
```

**Response — 201 Created:**
```json
{
  "id": 1,
  "name": "Vivek",
  "email": "vivek@example.com",
  "role": "USER",
  "createdAt": "2026-08-24T21:52:00",
  "updatedAt": "2026-08-24T21:52:00"
}
```

### Get All Users
```bash
curl http://localhost:8080/api/users
```

**Response — 200 OK:**
```json
[
  {
    "id": 1,
    "name": "Vivek",
    "email": "vivek@example.com",
    "role": "USER",
    "createdAt": "2026-08-24T21:52:00",
    "updatedAt": "2026-08-24T21:52:00"
  }
]
```

### Get User by ID
```bash
curl http://localhost:8080/api/users/1
```

### Delete User
```bash
curl -X DELETE http://localhost:8080/api/users/1
```

### Error Responses

**409 Conflict — duplicate email:**
```json
{
  "timestamp": "2026-08-24T21:52:00",
  "status": 409,
  "error": "Conflict",
  "message": "Email already exists: vivek@example.com",
  "path": "/api/users"
}
```

**404 Not Found:**
```json
{
  "timestamp": "2026-08-24T21:52:00",
  "status": 404,
  "error": "Not Found",
  "message": "User not found with id: 99",
  "path": "/api/users/99"
}
```

**400 Bad Request — validation failure:**
```json
{
  "timestamp": "2026-08-24T21:52:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed for one or more fields",
  "path": "/api/users",
  "errors": [
    "Password must be at least 8 characters"
  ]
}
```

---

## 7. PowerShell Examples (Windows)

```powershell
# Create user
Invoke-RestMethod -Uri http://localhost:8080/api/users -Method POST `
  -ContentType "application/json" `
  -Body '{"name":"Vivek","email":"vivek@example.com","password":"MySecurePassword123"}'

# Get all users
Invoke-RestMethod -Uri http://localhost:8080/api/users -Method GET

# Get user by ID
Invoke-RestMethod -Uri http://localhost:8080/api/users/1 -Method GET

# Delete user
Invoke-RestMethod -Uri http://localhost:8080/api/users/1 -Method DELETE
```

---

## 8. Architecture & Code Organization

### Request Flow

```
HTTP Request
    ↓  (Tomcat / DispatcherServlet)
UserController          ← Parses HTTP, validates @RequestBody, returns ResponseEntity
    ↓
UserService             ← Business logic: duplicate check, hash password, map entity↔DTO
    ↓
UserRepository          ← Spring Data JPA: SQL generated from method names
    ↓
PostgreSQL (users table)
```

### Package Structure

```
com.enterprise.aiknowledge
├── AiKnowledgePlatformApplication.java   ← @SpringBootApplication entry point
│
├── config
│   └── WebMvcConfig.java                ← CORS configuration
│
├── controller
│   ├── HealthController.java            ← GET /api/health
│   └── UserController.java             ← CRUD /api/users
│
├── dto
│   ├── HealthResponse.java             ← Health check response record
│   ├── CreateUserRequest.java          ← POST body with validation annotations
│   └── UserResponse.java              ← API response record (no passwordHash)
│
├── exception
│   ├── EmailAlreadyExistsException.java ← Thrown on duplicate email → 409
│   ├── ErrorResponse.java              ← Standardised error payload record
│   ├── GlobalExceptionHandler.java     ← @RestControllerAdvice, maps exceptions → HTTP
│   └── ResourceNotFoundException.java  ← Thrown when entity not found → 404
│
├── model
│   ├── Role.java                       ← Enum: USER | ADMIN
│   └── User.java                      ← @Entity mapped to "users" table
│
├── repository
│   └── UserRepository.java            ← JpaRepository + findByEmail + existsByEmail
│
└── service
    ├── PasswordHashingService.java    ← BCryptPasswordEncoder wrapper
    └── UserService.java              ← createUser, getAllUsers, getUserById, deleteUser
```

---

## 9. Security Notes

- Passwords are hashed with **BCrypt** (strength 10) before storage — never stored as plaintext
- `passwordHash` is **never returned** in any API response (not a field in `UserResponse`)
- Database credentials are read from environment variables — never hard-coded
- No secrets should be committed to version control (`.gitignore` covers `application-local.yml`)
