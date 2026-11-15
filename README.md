# palm-payment

- `palm-service` - Spring Boot backend (Java, Maven, PostgreSQL)
- `palm-rest-api` - C++ REST service that talks to the palm scanner SDK

## Database configuration

`palm-service` reads its database settings from environment variables:

| Variable      | Default                                 |
|---------------|-----------------------------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/palm` |
| `DB_USERNAME` | `postgres`                              |
| `DB_PASSWORD` | none (required)                         |

## Vendor SDK

The VP930 SDK is proprietary and is not part of this repository.
To build `palm-rest-api`, place it yourself at:

    palm-rest-api/vendor/vp930_sdk/include
    palm-rest-api/vendor/vp930_sdk/lib

`vendor/` is git-ignored.
