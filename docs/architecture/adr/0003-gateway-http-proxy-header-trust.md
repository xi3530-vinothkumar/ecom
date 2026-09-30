# ADR 0003: Gateway as a pure HTTP proxy; identity headers trusted via NetworkPolicy

- Status: Accepted (2026-09-29)
- Context: PLAN.md §5, §10; infra/k8s/base/platform/networkpolicies-ecom.yaml

## Context
Clients speak JSON/HTTP. Services talk to each other over gRPC. We need edge authentication without every service validating JWTs, and without gRPC-Web/transcoding at the edge.

## Decision
- The Spring Cloud Gateway validates the JWT (JWKS, ADR 0004) and forwards plain HTTP to each service's REST façade by path prefix. gRPC is strictly service-to-service.
- The Gateway's first filter **strips all client-supplied `X-User-*` and invalid `X-Correlation-Id` headers**, then stamps `X-User-Id` / `X-User-Roles` from the validated token. The raw JWT is not forwarded.
- Services trust these headers **only because the network guarantees the Gateway is the sole caller of their HTTP port**:
  - Default-deny NetworkPolicy in `ecom`. Port 8080 on `ecom.io/tier=service` pods admits only `app.kubernetes.io/name=gateway`.
  - gRPC 9090 admits only the specific callers (cart→catalog; order→cart/catalog/user/payment; payment→order/inventory; notification→user; user→cart).
  - Actuator runs on a separate management port (8081, `MANAGEMENT_SERVER_PORT`), reachable only by the OTel collector and kubelet probes. Scraping therefore never opens 8080.
  - Gateway ingress admits only the ALB subnets.
- Admin routes are checked at the Gateway **and** again in the owning service.

## Consequences
- One place for auth, rate limiting, CORS and header hygiene. Services stay simple and behave the same in Compose (where the Gateway is the only published port).
- Security depends on NetworkPolicy enforcement. The EKS VPC CNI network-policy agent must be enabled, and a negative test (a direct HTTP call from a non-gateway pod must fail) is part of environment verification.
- In-cluster traffic is plaintext (h2c for gRPC). mTLS via a mesh is the post-MVP hardening step, and needs no code change.
