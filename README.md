# Enterprise AI Knowledge Platform

A distributed, enterprise-grade AI knowledge platform for searching and querying large collections of documents using Retrieval-Augmented Generation (RAG), Hybrid Search, and Query-to-Chunk Reranking.

---

## Observability Architecture

The platform integrates enterprise-grade monitoring, metrics instrumentation, and visualization using **Spring Boot Actuator**, **Micrometer**, **Prometheus**, and **Grafana**.

```
+-------------------------------------------------------------+
|                  Enterprise AI Backend                      |
|                                                             |
|  +-------------------+  Metrics   +----------------------+  |
|  | Domain Services   | ---------> | PlatformMetrics      |  |
|  | - Search/Rerank   |            | - Micrometer Registry|  |
|  | - RAG Engine      |            +----------+-----------+  |
|  | - Document Pipeline|                       |              |
|  | - Redis / Qdrant  |                       v              |
|  +-------------------+            +----------------------+  |
|                                   | /actuator/prometheus |  |
|  +-------------------+            +----------+-----------+  |
|  | Health Indicators |                       |              |
|  | - PostgreSQL (db) |                       |              |
|  | - Redis (redis)   |                       |              |
|  | - Kafka (kafka)   |                       |              |
|  | - Qdrant (qdrant) |                       |              |
|  +---------+---------+                       |              |
|            |                                 |              |
|            v                                 |              |
|  +--------------------+                      |              |
|  | /actuator/health   |                      |              |
|  +--------------------+                      |              |
+----------------------------------------------+--------------+
                                               | Scrape (5s)
                                               v
                                    +--------------------+
                                    |     Prometheus     |
                                    |     Port 9090      |
                                    +----------+---------+
                                               | Query / Proxy
                                               v
                                    +--------------------+
                                    |      Grafana       |
                                    |     Port 3000      |
                                    +--------------------+
```

---

## Actuator Endpoints

Management endpoints are mounted under `/actuator`. Public and protected access is strictly controlled via `SecurityConfig`:

| Endpoint | Access | Description |
| :--- | :--- | :--- |
| `GET /actuator/health` | **Public** | Aggregated health status across PostgreSQL, Redis, Kafka, Qdrant, and disk |
| `GET /actuator/health/liveness` | **Public** | Kubernetes liveness probe (checks if JVM application is alive) |
| `GET /actuator/health/readiness`| **Public** | Kubernetes readiness probe (checks if application is ready to serve traffic) |
| `GET /actuator/info` | **Public** | General application and build information |
| `GET /actuator/prometheus` | **Public** | Prometheus / OpenMetrics text scrape endpoint |
| `GET /actuator/metrics` | **ADMIN Only** | Metric names catalog (requires JWT with `ROLE_ADMIN`) |
| `GET /actuator/metrics/{metric}`| **ADMIN Only** | Specific metric measurement details (requires JWT with `ROLE_ADMIN`) |

### Security & Privacy Protections
- Sensitive endpoints such as `/actuator/env`, `/actuator/beans`, and `/actuator/configprops` are **disabled from web exposure**. Unauthenticated attempts return `401 Unauthorized`; authenticated non-admin attempts return `403 Forbidden`; admin requests receive `404 Not Found`.
- Detailed health components (`show-details`) are only visible to authenticated users with `ROLE_ADMIN`.
- Health checks for external dependencies (Qdrant, Kafka, PostgreSQL, Redis) use short timeouts to avoid latency spikes or blocking health probes.

---

## Prometheus Configuration

Prometheus is configured in `infrastructure/prometheus/prometheus.yml` to scrape the Spring Boot backend at a 5-second interval:

```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: 'aikp-backend'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 5s
    static_configs:
      - targets: ['backend:8080']
```

- **Prometheus URL**: [http://localhost:9090](http://localhost:9090)
- **Scrape Status**: Navigate to **Status -> Targets** to verify `aikp-backend` is `UP`.

---

## Grafana Dashboards & Provisioning

Grafana is provisioned automatically with:
- **Datasource**: Pre-configured Prometheus datasource (`http://prometheus:9090`).
- **Dashboard**: `AI Knowledge Platform - Observability` (`uid: aikp-observability-overview`).

### Pre-Configured Dashboard Panels
1. **API Request Rate**: Requests per second broken down by HTTP method and URI.
2. **API Latency (p95)**: 95th percentile response times across endpoints.
3. **API Errors (5xx)**: HTTP 5xx error rate by URI and status code.
4. **Search Requests by Mode**: Request counts across `SEMANTIC`, `KEYWORD`, and `HYBRID` modes.
5. **Search Latency (Mean & Max)**: Average and peak latency by search mode.
6. **Reranking Latency & Fallbacks**: Query-to-chunk reranker duration and fallback rate to RRF.
7. **RAG Latency (Retrieval vs Generation)**: Detailed breakdown between chunk retrieval and Gemini generation phases.
8. **Redis Cache Hit / Miss Rate**: Hit and miss throughput across `search` and `rag` cache regions.
9. **Document Processing Failures**: Upload counts, completed documents, and pipeline phase failures (`extraction`, `chunking`, `embedding`, `indexing`).

- **Grafana URL**: [http://localhost:3000](http://localhost:3000)
- **Default Credentials**: `admin` / `admin`

---

## Metric Naming & Tag Cardinality Rules

All application domain metrics use the `aikp.` prefix and adhere strictly to **low-cardinality** standards:

### 1. Metric Catalog
- **Search**:
  - `aikp.search.requests` (Counter, tags: `mode`, `outcome`)
  - `aikp.search.latency` (Timer, tags: `mode`, `outcome`)
  - `aikp.search.results` (Counter/Summary, tags: `mode`)
  - `aikp.search.failures` (Counter, tags: `mode`, `reason`)
- **Reranking**:
  - `aikp.rerank.requests` (Counter, tags: `outcome`)
  - `aikp.rerank.latency` (Timer, tags: `outcome`)
  - `aikp.rerank.candidates` (DistributionSummary)
  - `aikp.rerank.status` (Gauge, 1 = enabled, 0 = disabled)
  - `aikp.rerank.fallbacks` (Counter)
  - `aikp.rerank.failures` (Counter, tags: `reason`)
- **RAG (Question Answering)**:
  - `aikp.rag.requests` (Counter, tags: `outcome`)
  - `aikp.rag.retrieval.latency` (Timer, tags: `outcome`)
  - `aikp.rag.generation.latency` (Timer, tags: `outcome`)
  - `aikp.rag.total.latency` (Timer, tags: `outcome`)
  - `aikp.rag.failures` (Counter, tags: `stage`, `reason`)
- **Redis Cache**:
  - `aikp.cache.hits` (Counter, tags: `cache`)
  - `aikp.cache.misses` (Counter, tags: `cache`)
  - `aikp.cache.read.failures` (Counter, tags: `cache`)
  - `aikp.cache.write.failures` (Counter, tags: `cache`)
  - `aikp.cache.invalidations` (Counter, tags: `target`)
- **Document Processing**:
  - `aikp.documents.uploaded` (Counter, tags: `status`)
  - `aikp.documents.processed` (Counter, tags: `outcome`)
  - `aikp.documents.processing.latency` (Timer, tags: `outcome`)
  - `aikp.documents.failed` (Counter, tags: `phase`)
  - `aikp.documents.by_status` (Gauge, tags: `status`)
- **Qdrant Vector Database**:
  - `aikp.qdrant.search.requests` (Counter, tags: `outcome`)
  - `aikp.qdrant.upsert.requests` (Counter, tags: `outcome`)
  - `aikp.qdrant.latency` (Timer, tags: `operation`)
  - `aikp.qdrant.errors` (Counter, tags: `operation`)
- **Kafka Event Streaming**:
  - `aikp.kafka.events.published` (Counter, tags: `topic`, `status`)
  - `aikp.kafka.events.consumed` (Counter, tags: `topic`, `status`)
  - `aikp.kafka.processing.failures` (Counter, tags: `reason`)

### 2. Prohibited High-Cardinality Tags & Privacy Rules
To protect memory and prevent metric explosion, the following are **strictly prohibited** in metric tags and application logs:
- `userId` / `user_id`
- Raw query text or search terms
- `documentId` / `document_id`
- `chunkId` / `chunk_id`
- JWT strings / Bearer tokens
- API keys or secrets
- Document extracted text or embeddings

---

## How to Run Locally

### 1. Start Infrastructure (PostgreSQL, Kafka, Qdrant, Redis, Prometheus, Grafana)
From the repository root:
```bash
docker compose -f infrastructure/docker-compose.yml up -d
```

To stop all services:
```bash
docker compose -f infrastructure/docker-compose.yml down
```

To stop all services and clear volumes:
```bash
docker compose -f infrastructure/docker-compose.yml down -v
```

### 2. Start Spring Boot Backend
```bash
cd backend
./mvnw spring-boot:run
```

### 3. Verify Observability Endpoints
- **Health Check**: `curl -s http://localhost:8080/actuator/health`
- **Prometheus Metrics**: `curl -s http://localhost:8080/actuator/prometheus`
- **Prometheus UI**: [http://localhost:9090](http://localhost:9090)
- **Grafana UI**: [http://localhost:3000](http://localhost:3000) (User: `admin`, Password: `admin`)
