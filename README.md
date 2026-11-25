# Patient Management System

A microservices-based backend for managing patient records, built with **Java 21** and **Spring Boot**. Services communicate over **REST**, **gRPC**, and **Kafka**, are secured with **JWT**, containerized with **Docker**, and the cloud infrastructure is defined as code with **AWS CDK (Java)** and tested locally on **LocalStack**.

## Architecture

```
                          ┌────────────────────────┐
   Client ───────────────▶│  API Gateway  (:4004)  │
                          │  Spring Cloud Gateway  │
                          └───────┬────────┬───────┘
                 /auth/**         │        │   /api/patients/**  (JWT required)
                                  ▼        ▼
                   ┌────────────────┐   ┌───────────────────┐
                   │  Auth Service  │   │  Patient Service  │
                   │    (:4005)     │   │      (:4000)      │
                   │  JWT + BCrypt  │   │  REST + JPA       │
                   └───────┬────────┘   └───┬──────────┬────┘
                           │                │          │
                      PostgreSQL        PostgreSQL     │
                                                       │
                          gRPC (:9001)  ◀──────────────┤
                                ▼                      │ Kafka topic "patient"
                       ┌─────────────────┐             ▼
                       │ Billing Service │     ┌──────────────────┐
                       │ (:4001 / :9001) │     │ Analytics Service│
                       └─────────────────┘     │     (:4002)      │
                                               └──────────────────┘
```

| Service | Port | Responsibility |
|---|---|---|
| `api-gateway` | 4004 | Single entry point. Routes requests and validates JWTs with a custom gateway filter before forwarding to protected routes. |
| `auth-service` | 4005 | Login, password verification (BCrypt), JWT generation and validation. Backed by PostgreSQL. |
| `patient-service` | 4000 | Patient CRUD REST API with validation, JPA persistence, OpenAPI docs. Calls Billing over gRPC and publishes events to Kafka. |
| `billing-service` | 4001 / 9001 | gRPC server that creates a billing account for a new patient. |
| `analytics-service` | 4002 | Kafka consumer that receives patient events (Protocol Buffers). |
| `infrastructure` | - | AWS CDK (Java) stack: VPC, RDS PostgreSQL, MSK, ECS Fargate, Application Load Balancer. |
| `integration-tests` | - | REST Assured tests that run against the gateway. |

## Request flow: creating a patient

1. The client calls `POST /auth/login` and receives a JWT.
2. The client calls `POST /api/patients` with `Authorization: Bearer <token>`.
3. The **API Gateway** asks the Auth service to validate the token. Invalid or missing tokens get `401`; valid requests are forwarded.
4. **Patient Service** validates the payload, checks for a duplicate email, and saves the patient to PostgreSQL.
5. Patient Service calls **Billing Service** over **gRPC** to create a billing account.
6. Patient Service publishes a `PATIENT_CREATED` event (Protocol Buffers) to the `patient` Kafka topic.
7. **Analytics Service** consumes the event.

## Tech stack

- **Language / Framework:** Java 21, Spring Boot, Spring Web, Spring Data JPA, Spring Security, Spring Cloud Gateway
- **Communication:** REST, gRPC, Protocol Buffers, Apache Kafka
- **Security:** JWT (jjwt), BCrypt
- **Database:** PostgreSQL (H2 available for local testing)
- **API docs:** springdoc OpenAPI / Swagger UI
- **Containers and cloud:** Docker (multi-stage builds), AWS CDK (Java), LocalStack, ECS Fargate, RDS, MSK, CloudWatch Logs
- **Testing / build:** JUnit 5, REST Assured, Maven

## API overview

Through the gateway (`http://localhost:4004`):

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/auth/login` | No | Returns a JWT for valid credentials |
| GET | `/api/patients` | Yes | List patients |
| POST | `/api/patients` | Yes | Create a patient |
| PUT | `/api/patients/{id}` | Yes | Update a patient |
| DELETE | `/api/patients/{id}` | Yes | Delete a patient |

Swagger docs are exposed through the gateway at `/api-docs/patients` and `/api-docs/auth`. Sample requests are in `api-requests/` and `grpc-requests/`.

## Running locally

**Prerequisites:** JDK 21, Maven, Docker, and (for the cloud setup) [LocalStack](https://www.localstack.cloud/) plus the AWS CLI.

### 1. Build the Docker image for each service

```bash
docker build -t patient-service  ./patient-service
docker build -t auth-service     ./auth-service
docker build -t billing-service  ./billing-service
docker build -t analytics-service ./analytics-service
docker build -t api-gateway      ./api-gateway
```

### 2. Set the JWT secret

The Auth service reads the signing key from the `JWT_SECRET` environment variable (a Base64-encoded key). Generate one and keep it out of version control:

```bash
openssl rand -base64 32
export JWT_SECRET="<generated key>"
```

### 3. Synthesize and deploy the stack to LocalStack

```bash
cd infrastructure
mvn compile exec:java -Dexec.mainClass="com.pm.LocalStack"   # generates cdk.out/
./localstack-deploy.sh                                        # deploys to LocalStack
```

The script prints the load balancer DNS name once the stack is up.

### 4. Try it out

```bash
# Log in
curl -X POST http://<load-balancer-dns>:4004/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"testuser@test.com","password":"password123"}'

# Call a protected endpoint with the returned token
curl http://<load-balancer-dns>:4004/api/patients \
  -H "Authorization: Bearer <token>"
```

`testuser@test.com` is a seeded test account used by the integration tests.

### Run the integration tests

With the stack running and the gateway reachable at `localhost:4004`:

```bash
cd integration-tests
mvn test
```

## Project structure

```
Patient-Management/
├── api-gateway/          # Spring Cloud Gateway + JWT filter
├── auth-service/         # Authentication, JWT, users
├── patient-service/      # Patient CRUD, gRPC client, Kafka producer
├── billing-service/      # gRPC server
├── analytics-service/    # Kafka consumer
├── infrastructure/       # AWS CDK stack (Java) for LocalStack
├── integration-tests/    # REST Assured tests
├── api-requests/         # Sample HTTP requests
└── grpc-requests/        # Sample gRPC requests
```

## Possible improvements

- Implement real billing logic and persistence in Billing Service.
- Add analytics processing instead of logging events.
- Use the outbox pattern, retries, or a circuit breaker for Kafka and gRPC failures.
- Enforce role-based access control using the role claim in the JWT.
- Add unit tests (JUnit and Mockito) for the service layer.
