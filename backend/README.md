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
- Role-based authorization rules:
  - `GET /api/health`, `POST /api/auth/login`, `POST /api/users` are **public**
  - `GET /api/users` (list all) and `DELETE /api/users/{id}` require **`ADMIN`** role
  - `GET /api/users/{id}` and `GET /api/auth/me` require **authenticated user (`USER` or `ADMIN`)**
- Custom JSON 401 Unauthorized (`SecurityAuthenticationEntryPoint`) and 403 Forbidden (`SecurityAccessDeniedHandler`) responses
- Generic authentication error handling (prevents user enumeration attacks)
- Comprehensive automated test suite (29 tests passing)

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

### Setting Environment Variables

**Windows (PowerShell):**
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="your_secure_password"
$env:JWT_SECRET="your_strong_random_secret_key_minimum_32_chars"
```

**Linux / macOS:**
```bash
export DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
export DB_USERNAME="postgres"
export DB_PASSWORD="your_secure_password"
export JWT_SECRET="your_strong_random_secret_key_minimum_32_chars"
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

### HTTP Status Codes

| Code | Meaning | Scenario |
| :--- | :--- | :--- |
| `200 OK` | Success | Read, delete, or successful login |
| `201 Created` | Created | User registered successfully |
| `400 Bad Request` | Validation Error | Blank fields, malformed email, short password |
| `401 Unauthorized` | Auth Required / Bad Credentials | Missing/expired/invalid JWT, or invalid login credentials |
| `403 Forbidden` | Access Denied | Authenticated user lacks required role (e.g. `USER` calling `GET /api/users`) |
| `404 Not Found` | Resource Not Found | User with given ID does not exist |
| `409 Conflict` | Duplicate Resource | Email already registered |

---

## 6. Example Requests & Usage Flow

### 1. Register a User Account (Public)
```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Vivek",
    "email": "vivek@example.com",
    "password": "MySecurePassword123"
  }'
```

### 2. Login to get JWT Token (Public)
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "vivek@example.com",
    "password": "MySecurePassword123"
  }'
```

**Response (200 OK):**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ2aXZla0BleGFtcGxlLmNvbSIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNzI0NTQwMDAwLCJleHAiOjE3MjQ1NDM2MDB9.signature",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

### 3. Access Protected Profile Endpoint using Bearer Token
```bash
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer <YOUR_ACCESS_TOKEN>"
```

### 4. Admin Access Example (List All Users)
```bash
curl http://localhost:8080/api/users \
  -H "Authorization: Bearer <ADMIN_ACCESS_TOKEN>"
```

---

## 7. Architecture & Authentication Flow

### JWT Authentication Request Flow

```
HTTP Request with "Authorization: Bearer <jwt>"
    ↓
SecurityConfig (FilterChain)
    ↓
JwtAuthenticationFilter (OncePerRequestFilter)
    ├─ Extract Bearer token header
    ├─ Verify HMAC-SHA256 signature & expiration via JwtService
    ├─ Read 'sub' (email) & 'role' claims directly from token
    └─ Set SecurityContextHolder.getContext().setAuthentication(auth)  [No DB query!]
    ↓
Spring Security Authorization Manager
    ├─ Match path against permitAll() / hasRole() rules
    ├─ 401 Unauthorized (if unauthenticated & endpoint protected)
    └─ 403 Forbidden (if authenticated but role insufficient)
    ↓
RestController (AuthController / UserController)
```

### Package Structure

```
com.enterprise.aiknowledge
├── AiKnowledgePlatformApplication.java   ← @SpringBootApplication entry point
│
├── config
│   ├── SecurityConfig.java              ← Spring Security FilterChain & Beans
│   └── WebMvcConfig.java                ← CORS configuration
│
├── controller
│   ├── AuthController.java              ← POST /api/auth/login, GET /api/auth/me
│   ├── HealthController.java            ← GET /api/health
│   └── UserController.java              ← CRUD /api/users
│
├── dto
│   ├── CreateUserRequest.java          ← User registration payload
│   ├── HealthResponse.java             ← Health check response record
│   ├── LoginRequest.java               ← Login payload
│   ├── LoginResponse.java              ← JWT token response payload
│   └── UserResponse.java               ← User profile record (no passwordHash)
│
├── exception
│   ├── EmailAlreadyExistsException.java ← Duplicate email error → 409
│   ├── ErrorResponse.java              ← Standardized JSON error response
│   ├── GlobalExceptionHandler.java     ← Translates exceptions to HTTP responses
│   └── ResourceNotFoundException.java  ← Not found error → 404
│
├── model
│   ├── Role.java                       ← Enum: USER | ADMIN
│   └── User.java                       ← JPA Entity ("users" table)
│
├── repository
│   └── UserRepository.java             ← JpaRepository + findByEmail + existsByEmail
│
├── security
│   ├── JwtAuthenticationFilter.java     ← OncePerRequestFilter for JWT validation
│   ├── JwtService.java                  ← JJWT Token generation, parsing & claims
│   ├── SecurityAccessDeniedHandler.java ← Custom 403 JSON handler
│   └── SecurityAuthenticationEntryPoint.java ← Custom 401 JSON handler
│
└── service
    ├── AuthService.java                 ← Login authentication logic
    ├── PasswordHashingService.java     ← BCrypt hashing & matching service
    └── UserService.java                 ← User CRUD business logic
```

---

## 8. Security Highlights

- **Stateless & Scalable:** Zero server-side session state. All identity & role claims reside within the signed JWT.
- **Constant-Time BCrypt Hashing:** Passwords verified via timing-safe BCrypt comparison (`BCryptPasswordEncoder`).
- **Protection Against User Enumeration:** Authentication failures return generic `"Invalid email or password"` (401) regardless of whether email or password was wrong.
- **Clean Exception Separation:** Controller exceptions are handled by `@RestControllerAdvice`, while Spring Security filter-level errors (401/403) are handled by custom security entry points writing standardized JSON error payloads.
