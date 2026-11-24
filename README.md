# palm-payment

Palm-vein biometric identification for payments. A customer is registered once by scanning their palm. Later, a palm scan identifies them and returns their customer details.

The repository contains two services:

| Module | Stack | Role |
|--------|-------|------|
| [`palm-service`](palm-service) | Java 17, Spring Boot 4, Maven, PostgreSQL | Business API. It stores users and palm templates and exposes the public REST API. |
| [`palm-rest-api`](palm-rest-api) | C++17, CMake, cpp-httplib, nlohmann/json | Device gateway. It wraps the VP930 palm scanner SDK and exposes it over local HTTP. |

## Architecture

```
 Client / Frontend                 palm-service (Spring Boot)             palm-rest-api (C++)
 (http://localhost:5173)           port 8080                              port 8081
        │                                │                                      │
        │  POST /api/v1/palm/register    │                                      │
        ├───────────────────────────────►│  POST /palm/register                 │
        │                                ├─────────────────────────────────────►│── scan palm (VP930)
        │                                │◄─────────────────────────────────────┤   IR + RGB features
        │                                │── save user + templates (PostgreSQL) │
        │◄───────────────────────────────┤                                      │
        │                                │                                      │
        │  POST /api/v1/palm/identify    │  load all templates from DB          │
        ├───────────────────────────────►│  POST /palm/identify (+ candidates)  │
        │                                ├─────────────────────────────────────►│── scan + compare
        │                                │◄─────────────────────────────────────┤   matched finCode
        │◄───────────────────────────────┤  look up user by finCode             │
```

- **Registration.** `palm-service` asks the scanner service to capture a palm. It receives infrared (IR) and RGB feature vectors, encoded as base64 raw floats, and stores them with the user.
- **Identification.** `palm-service` sends all stored templates to the scanner service as candidates. The scanner captures a live palm and compares it against them. It returns the best match, and `palm-service` resolves that match to a user.

Both services must run on the machine the scanner is attached to. `palm-service` currently calls the scanner service at the hardcoded address `http://localhost:8081`.

## Prerequisites

- JDK 17+
- PostgreSQL 13+ with an empty database named `palm`
- For `palm-rest-api`:
  - Windows, because the CMake file links `ws2_32` and defines `_WIN32_WINNT`
  - A C++17 compiler and CMake 3.10+
  - The VP930 palm scanner, with its SDK (see [Vendor SDK](#vendor-sdk))

## Quick start

### 1. Database

Start PostgreSQL with Docker Compose:

```bash
cp .env.example .env        # then edit .env and set DB_PASSWORD
docker compose up -d
```

This runs PostgreSQL 16 on port `5432` with a `palm` database and a persistent volume. Without Docker, run `createdb palm` on an existing PostgreSQL instead.

### 2. Run `palm-service`

```bash
cd palm-service
export DB_PASSWORD=your_password        # required, same value as in .env
./mvnw spring-boot:run
```

The API listens on `http://localhost:8080`. Flyway applies the schema migrations in `palm-service/src/main/resources/db/migration` on startup, and Hibernate validates the entities against the result (`ddl-auto: validate`). Databases previously created by Hibernate are baselined automatically.

### 3. Build and run `palm-rest-api`

```bash
cd palm-rest-api
cmake -S . -B build
cmake --build build --config Release
./build/palm_service        # or build\Release\palm_service.exe on Windows
```

It listens on `http://0.0.0.0:8081`. Stop it with `Ctrl+C`. It shuts down cleanly on `SIGINT` and `SIGTERM`.

Check that it's up:

```bash
curl http://localhost:8081/health
# {"status":"UP"}
```

## Configuration

`palm-service` reads these environment variables:

| Variable      | Default                                 | Description          |
|---------------|-----------------------------------------|----------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/palm` | JDBC connection URL  |
| `DB_USERNAME` | `postgres`                              | Database user        |
| `DB_PASSWORD` | none (**required**)                     | Database password    |

CORS currently allows `http://localhost:5173` on `/api/**`. The scanner service port (`8081`) is fixed in code.

## API reference

Interactive docs are served by Swagger UI at `http://localhost:8080/swagger-ui.html` while `palm-service` is running.

### `palm-service` (public API, port 8080)

Base path: `/api/v1/palm`

#### `POST /register`

Registers a new user and captures their palm. The user places their palm on the scanner when the request is made.

Request:

```json
{
  "fullName": "Jane Doe",
  "finCode": "1ABC234",
  "customerNo": "C-10001"
}
```

- `finCode` is required and unique.
- If the `finCode` is already registered, the existing registration is returned with the message `"User already registered"`. No new scan is performed.

Response:

```json
{
  "success": true,
  "message": "...",
  "templateId": 1,
  "palmType": 1,
  "irCount": 512,
  "irFeaturesB64": "...",
  "rgbCount": 512,
  "rgbFeaturesB64": "..."
}
```

`palmType` is `0` for the left palm and `1` for the right palm.

#### `POST /identify?timeoutMs=8000`

Captures a palm and identifies the registered user.

| Parameter   | Default | Description                           |
|-------------|---------|---------------------------------------|
| `timeoutMs` | `8000`  | How long to wait for a palm, in ms.   |

Response `200`:

```json
{
  "fullName": "Jane Doe",
  "finCode": "1ABC234",
  "customerNo": "C-10001"
}
```

Errors:

| Status | Meaning                                                          |
|--------|------------------------------------------------------------------|
| `404`  | No registered templates, no match found, or matched user missing |
| `502`  | Scanner service returned an empty or incomplete response         |

#### `GET /`

Returns all stored templates (`finCode`, `templateId`, `irFeatureBase64`, `rgbFeatureBase64`).

### `palm-rest-api` (scanner gateway, port 8081)

This is an internal service and should not be exposed publicly.

| Method | Path             | Description                                                  |
|--------|------------------|--------------------------------------------------------------|
| `GET`  | `/health`        | Liveness check.                                              |
| `POST` | `/palm/register` | Captures a palm and returns IR/RGB feature vectors (base64). |
| `POST` | `/palm/identify` | Captures a palm and matches it against the given candidates. |

`POST /palm/identify` body:

```json
{
  "timeoutMs": 8000,
  "candidates": [
    {
      "finCode": "1ABC234",
      "templateId": 1,
      "irFeatureBase64": "...",
      "rgbFeatureBase64": "..."
    }
  ]
}
```

The response includes `success`, `message`, `matchedTemplateId`, `matchedFinCode`, `irScore`, `rgbScore`, `irThreshold`, `rgbThreshold` and `palmType`. It returns `400` if `candidates` is missing or contains no valid entry.

## Database migrations

Schema changes are versioned with [Flyway](https://flywaydb.org). To change the schema, add a new file such as `V2__add_column.sql` under `palm-service/src/main/resources/db/migration`. Never edit a migration that has already been applied.

## Tests

```bash
cd palm-service
./mvnw test -Dtest=PalmServiceTest      # unit tests, no database needed
./mvnw test                             # also runs the context-load test, which needs PostgreSQL and DB_PASSWORD
```

## Project structure

```
palm-payment/
├── palm-service/                         Spring Boot backend
│   └── src/main/java/az/palm/palmservice/
│       ├── controllers/                  REST endpoints
│       ├── services/                     Business logic, scanner client
│       ├── domain/entities|repositories/ JPA model (PalmUser, PalmInfo)
│       ├── dtos/                         Request/response types
│       └── configs/                      CORS, HTTP clients, Swagger
└── palm-rest-api/                        C++ scanner gateway
    ├── main.cpp                          HTTP routes
    ├── PalmService.cpp|h                 VP930 SDK wrapper (capture, compare)
    └── third_party/                      cpp-httplib, nlohmann/json (single-header)
```

## Vendor SDK

The VP930 SDK is proprietary and is not part of this repository. To build `palm-rest-api`, place it yourself at:

    palm-rest-api/vendor/vp930_sdk/include
    palm-rest-api/vendor/vp930_sdk/lib

`vendor/` is git-ignored.

## License

Released under the [MIT License](LICENSE). The proprietary VP930 SDK is not covered by it.

## Known limitations

- Endpoints have no authentication or authorization.
- Biometric templates are stored as base64 text in the database, unencrypted.
- `identify` sends every stored template to the scanner on each request, so it does not scale to large user bases.
- The scanner URL and CORS origin are hardcoded.
- `palm-rest-api` only builds on Windows.
- Test coverage is minimal.
