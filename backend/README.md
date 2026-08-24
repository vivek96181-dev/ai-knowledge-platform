# AI Knowledge Platform - Backend Foundation

Spring Boot 3 backend service for the Enterprise AI Knowledge Platform.

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

The application follows 12-Factor methodology and accepts the following environment variables (with default values provided for local development):

| Variable | Description | Default Value |
| :--- | :--- | :--- |
| `SERVER_PORT` | Port the Spring Boot server listens on | `8080` |
| `DB_URL` | JDBC connection string to PostgreSQL | `jdbc:postgresql://localhost:5432/ai_knowledge_db` |
| `DB_USERNAME` | PostgreSQL database user | `postgres` |
| `DB_PASSWORD` | PostgreSQL database password | `postgres` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed frontend origins | `http://localhost:3000,http://localhost:5173` |

### Setting Environment Variables (Optional)

**Windows (PowerShell):**
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="your_password"
```

**Linux / macOS:**
```bash
export DB_URL="jdbc:postgresql://localhost:5432/ai_knowledge_db"
export DB_USERNAME="postgres"
export DB_PASSWORD="your_password"
```

---

## 4. Build and Run the Application

Navigate to the `backend` directory:
```bash
cd backend
```

### Run Tests:
**Windows:**
```powershell
.\mvnw.cmd test
```

**Linux / macOS:**
```bash
./mvnw test
```

### Start Development Server:
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

## 5. Testing the Health Check API

Once the server is running, you can test the health endpoint:

### Using cURL:
```bash
curl -X GET http://localhost:8080/api/health
```

### Using PowerShell:
```powershell
Invoke-RestMethod -Uri http://localhost:8080/api/health -Method GET
```

### Expected Response:
```json
{
  "status": "UP",
  "service": "ai-knowledge-platform"
}
```

---

## 6. Architecture & Code Organization

```
com.enterprise.aiknowledge
├── config          # Cross-cutting configurations (CORS, Jackson, WebMvc)
├── controller      # REST endpoints (HealthController, etc.)
├── dto             # Data Transfer Objects (HealthResponse, ErrorResponse)
├── exception       # GlobalExceptionHandler (@RestControllerAdvice)
├── repository      # Spring Data JPA repositories
└── service         # Business logic layer
```
