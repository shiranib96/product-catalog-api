# Product Catalog API

Product catalog service for the JDriven case. All product information (names, descriptions, brand,
category, specs) and prices come from an external source: the service pulls the source's catalog
periodically, and the source pushes changes to a webhook. Everything is stored in our own data model
in PostgreSQL, with a full-text search on top.

Java 25, Spring Boot 4.1 and PostgreSQL 18. The `mock-source` folder has a small Python server that
plays the source, so the whole thing can be tried out locally.

```
                         GET /api/products  (on startup + every few minutes)
   product service  ───────────────────────────────────────────────────►  source
   (Spring Boot)    ◄───────────────────────────────────────────────────  (mock-source/server.py)
         │               POST /api/v1/product-updates  (webhook, signed)
         ▼
     PostgreSQL
```

## Requirements

- **JDK 25** (the current Java LTS). Maven has to run on it too: `./mvnw -v` shows which Java it uses.
  With an older JDK the build stops right away and tells you so.
  - macOS: `brew install --cask temurin@25`, then `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`
  - Windows / Linux: download Temurin 25 from https://adoptium.net and set `JAVA_HOME` to it, or use
    [SDKMAN](https://sdkman.io) (`sdk list java` shows the Temurin 25 version to install)
  - Maven itself isn't needed, `./mvnw` (`mvnw.cmd` on Windows) downloads the right version.
- **Python 3**, only for the mock source (no packages needed).
- **Docker**, only for docker compose. With Docker you don't need a JDK at all: `docker compose up --build`
  builds the service inside a JDK 25 container.

## How it works

- **Pull.** On startup and then every `sync-interval`, the service fetches the source's whole catalog
  (page by page): all product data plus the current price. It's mapped to our own `Product` model and
  stored. Products with missing or invalid data are skipped and logged.
- **Push.** The service registers a webhook with the source. The source calls it when something
  changes: changed or new products (with all their data) and/or price changes. Calls must be signed with
  a shared secret, otherwise they are rejected.
- **Newer data wins.** Product data comes with the source's `lastModified`, a price with its
  `validFrom`. We only store data that is newer than what we already have, and that check is part of the
  SQL statement itself (`CatalogWriter`). So duplicate calls, calls in the wrong order and the pull
  crossing the webhook can never replace newer data with older data, and nothing needs to be locked.
- If a webhook call gets lost, the next pull fixes it. After the first import, the metric
  `catalog_updates_total{channel="sync",outcome="applied"}` shows how often that happens.

## Running it

See [Requirements](#requirements) first.

```bash
./mvnw verify
```

Runs all unit and integration tests. The integration tests use an embedded PostgreSQL and a stub
source, so they don't need Docker or Python.

### Locally, without Docker

Start the mock source in one terminal:

```bash
python3 mock-source/server.py
```

And the service in another:

```bash
./mvnw spring-boot:test-run
```

This runs the service with an embedded PostgreSQL. It imports the 100 mock products straight away and
registers its webhook. From then on the mock source keeps sending changes: prices every 10 seconds, a
product change every 30 seconds and a new product every minute. The sync runs every minute in this mode.

- Search: http://localhost:8080/api/v1/products/search?q=coffee%20black
- Swagger UI: http://localhost:8080/swagger-ui.html
- Trigger changes yourself:
  `curl -X POST 'localhost:9090/api/price-changes?count=5'`,
  `curl -X POST 'localhost:9090/api/product-changes?count=2'` or
  `curl -X POST localhost:9090/api/new-products`

### With Docker Compose

```bash
docker compose up --build
```

Starts PostgreSQL, the mock source (port 9090) and the service (port 8080). No Java or Maven needed on
your machine for this, the service is built inside a JDK 25 container.

## API

### `GET /api/v1/products/search`

| Parameter | Description                                 |
|-----------|---------------------------------------------|
| `q`       | the search text (required, max 200 chars)   |
| `page`    | page number, starts at 0                    |
| `size`    | results per page, default 20, max 100       |

Searches SKU, name, brand, category, attribute values and description:

- all words have to match, partial words count too (`headph` finds headphones)
- results are ranked on where the match is: name/SKU first, then brand/category, attributes, description
- when nothing matches it falls back to fuzzy matching, so typos still find something (`moccamastr`)

Examples (piping through `python3 -m json.tool` just formats the JSON):

```bash
# all products matching both "coffee" and "black"
curl -s 'http://localhost:8080/api/v1/products/search?q=coffee%20black' | python3 -m json.tool

# the same, 5 results per page
curl -s 'http://localhost:8080/api/v1/products/search?q=coffee%20black&page=0&size=5' | python3 -m json.tool

# let curl encode the search text
curl -s -G 'http://localhost:8080/api/v1/products/search' --data-urlencode 'q=coffee black' | python3 -m json.tool
```

Keep the quotes around the URL, otherwise the shell treats `&` as its own operator.

A search with a typo still finds the product:

```bash
curl -s 'http://localhost:8080/api/v1/products/search?q=moccamastr' | python3 -m json.tool
```

```json
{
  "items": [
    {
      "sku": "KIT-3001",
      "name": "Moccamaster KBG Select Filter Coffee Maker",
      "description": "Handmade filter coffee maker from the Netherlands that brews a full jug in six minutes.",
      "brand": "Technivorm",
      "category": "Home > Kitchen > Coffee makers",
      "attributes": {"color": "Matt Black", "capacity": "1.25 l", "countryOfOrigin": "Netherlands"},
      "price": {"amount": 259.00, "currency": "EUR", "updatedAt": "2026-09-12T08:31:02.114Z"},
      "createdAt": "2026-09-14T09:12:44.402Z",
      "updatedAt": "2026-09-14T09:12:44.402Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

### `POST /api/v1/product-updates`

The webhook the source calls. The body is in the source's format and has changed or new products
and/or price changes:

```json
{
  "eventId": "0b8f5a7e-6f0e-4a55-a0f1-7c3b1e2d9c11",
  "sentAt": "2026-09-14T10:15:30.500Z",
  "productChanges": [
    {
      "articleNumber": "NEW-9902",
      "title": "Fairphone 6 Smartphone 256GB",
      "details": "Modular, repairable smartphone with a brighter screen and a replaceable battery.",
      "manufacturer": "Fairphone",
      "categoryPath": "Electronics > Phones > Smartphones",
      "specs": [{"name": "color", "value": "Moss Green"}, {"name": "storage", "value": "256 GB"}],
      "lastModified": "2026-09-14T10:15:30.123Z",
      "price": {"amount": "649.00", "currency": "EUR"},
      "priceValidFrom": "2026-09-14T10:15:30.123Z"
    }
  ],
  "priceChanges": [
    {"articleNumber": "AUD-1001", "price": {"amount": "359.00", "currency": "EUR"}, "validFrom": "2026-09-14T10:15:30.123Z"}
  ]
}
```

It has to be signed: `X-Signature: sha256=<hex HMAC-SHA256 of the raw body, with the shared secret>`.

| Response | When                                                                                                        |
|----------|-------------------------------------------------------------------------------------------------------------|
| `200`    | `{"products": {"applied": 1, "stale": 0}, "prices": {"applied": 2, "stale": 0}, "unknownProducts": []}`     |
| `400`    | the body isn't valid JSON, has no changes or has invalid values (the fields are listed in `errors`)         |
| `401`    | the signature is missing or wrong                                                                           |

Products are handled before prices, so a new product and its price can come in the same call. `stale`
means we already had the same or newer data. Prices for products we don't know yet are listed in
`unknownProducts` but aren't an error: if the source has the product, the next sync imports it.

Sending a price change by hand, with the local default secret:

```bash
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
body='{"priceChanges":[{"articleNumber":"AUD-1001","price":{"amount":"299.00","currency":"EUR"},"validFrom":"'$now'"}]}'
signature=$(printf '%s' "$body" | openssl dgst -sha256 -hmac local-dev-secret | sed 's/^.* //')
curl -X POST localhost:8080/api/v1/product-updates -H 'Content-Type: application/json' \
     -H "X-Signature: sha256=$signature" -d "$body"
```

Errors are returned as problem details (RFC 9457). The OpenAPI spec is at `/v3/api-docs`.

## Mock source

`mock-source/server.py`, Python standard library only. Its product format is deliberately different
from ours (`articleNumber`, `title`, `manufacturer`, `categoryPath`, a list of `specs`, ...), the service
maps it in `SourceMapper`.

| Endpoint                            | Description                                                        |
|-------------------------------------|--------------------------------------------------------------------|
| `GET /api/products?page=&size=`     | the products with all their data and current price, paged          |
| `POST /api/webhooks`                | register a webhook: `{"url": "...", "secret": "..."}`              |
| `GET /api/webhooks`                 | registered webhooks                                                |
| `POST /api/price-changes?count=3`   | change some random prices now                                      |
| `POST /api/product-changes?count=1` | change some random products now (availability, extra details)      |
| `POST /api/new-products`            | release the next new product from `new-products.csv`               |

Every `CHANGE_INTERVAL` seconds (default 10, `0` turns it off) it changes 1 to 3 prices, every third
time also a product's data and every sixth time it releases a new product. Every change is sent to the
registered webhooks, and failed calls are retried a few times with a back-off, like a real source
would. The catalog is in `mock-source/products.csv` (100 products), the 4 upcoming products in
`mock-source/new-products.csv`.

## Choices

### Pull and push

Push gets changes in right away. Pull is how the catalog gets in the first time, and it's the safety
net for webhook calls that got lost. Either one alone isn't enough: webhooks alone lose changes when
we're down longer than the source keeps retrying, and pull alone is only as fresh as the interval.

### Newer data wins, without locking

Loading a product, comparing timestamps and saving it would need locking, because the pull and the
webhook can touch the same product at the same time. Instead every write is one statement that only
applies newer data: `INSERT ... ON CONFLICT (sku) DO UPDATE ... WHERE source_updated_at < new` for product
data and `UPDATE ... WHERE price_updated_at < new` for prices. The database does the check and the write
in one go. That's also why JPA is only used for reading (the entity is `@Immutable`).

### One service now, more throughput later (stage 2)

The webhook does a few small SQL statements per call, which PostgreSQL handles fine, so for now
everything runs in one service. If the load grows (bulk updates from sources, writes slowing down
searches), the next step is:

- the product service only accepts incoming updates and answers reads; it puts updates on Kafka and
  answers `202` right away
- a separate sync service consumes them, maps them to our model and writes them to PostgreSQL at the
  database's pace, retrying failures a few times
- updates that still fail (e.g. a mapping error) go to a dead letter queue with monitoring and alerts,
  and can be replayed

The product model, the "newer data wins" rule, the mapping and the search stay the same.

### Signed webhook

The webhook has to be reachable from outside, so without a check anyone could change our products and
prices. The HMAC signature proves the call comes from someone with the secret and that the body wasn't
changed. It's compared in constant time. Replaying an old call doesn't do anything, because older data
is ignored.

### Our own data model

Only the `source` package knows the source's format (`SourceProduct`, `SourceEvent`,
`SourceMapper`). The rest of the code only knows our `ProductUpdate`, `PriceUpdate` and `Product`. If
the source changes its API, or we add a second source, only that package changes.

### PostgreSQL for search instead of Elasticsearch

For this size of catalog PostgreSQL full-text search is fast enough (a generated `tsvector` column
with a GIN index, plus `pg_trgm` for the typo fallback), and it's the database we need anyway.
Elasticsearch would mean another system to run and a second copy of the data to keep in sync.
`SearchPerformanceIT` loads 100,000 products and checks that searches stay under a second; on my
laptop they took between 2 and 60 ms.

### Tests without Docker

The integration tests run against a real PostgreSQL (embedded binaries, same major version as
production) because most of the logic is in the SQL. A small JDK HTTP server plays the source in the
tests. So the build only needs a JDK.

## Production

- Docker image: multi-stage build, runs as non-root user, JVM memory based on the container limit
- all config through environment variables, see below
- health checks for Kubernetes: `/actuator/health/liveness` and `/actuator/health/readiness` (includes the database)
- Prometheus metrics at `/actuator/prometheus`, including `catalog_updates_total` (by type, channel and
  outcome) and `catalog_update_lag_seconds` (time from a change at the source until the webhook applied it)
- JSON logging in the container (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)
- database migrations with Flyway on startup
- GitHub Actions workflow that runs the tests and builds the image

| Environment variable                    | Default                                                 |
|-----------------------------------------|---------------------------------------------------------|
| `SPRING_DATASOURCE_URL`                 | `jdbc:postgresql://localhost:5432/catalog`              |
| `SPRING_DATASOURCE_USERNAME`            | `catalog`                                               |
| `SPRING_DATASOURCE_PASSWORD`            | `catalog`                                               |
| `CATALOG_SOURCE_BASE_URL`             | `http://localhost:9090`                                 |
| `CATALOG_SOURCE_SYNC_INTERVAL`        | `PT5M`                                                  |
| `CATALOG_SOURCE_WEBHOOK_CALLBACK_URL` | `http://localhost:8080/api/v1/product-updates`          |
| `CATALOG_SOURCE_WEBHOOK_SECRET`       | `local-dev-secret` (always set this outside local dev)  |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE`     | plain text, set to `ecs` for JSON                       |

## Code

```
src/main/java/nl/casus/catalog
  product/    our product model (read-only entity) and the search endpoint
  update/     applying product and price updates, only when newer (CatalogWriter has the SQL)
  source/   everything about the source: client, sync, webhook, its format and the mapping
  web/        error handling, OpenAPI info
src/main/resources/db/migration   Flyway migrations
src/test/java/nl/casus/catalog
  support/    embedded PostgreSQL and the source stub for the tests
mock-source/                    the mock source (Python)
```

## Not done / next steps

- Products that disappear at the source stay in our catalog. The source could send removals, or
  the full sync could mark products it didn't see as inactive.
- With more than one source, decide which source wins when two of them have the same product (for now
  it's simply the newest change).
- With several instances, each one runs the sync. That's harmless (it's idempotent) but wasteful;
  ShedLock or a single scheduled job would fix it.
- For big catalogs, fetch only what changed since the last sync instead of everything.
- More product data if the source has it, like images or EAN codes. It would go through the same mapping.
- A timestamp in the webhook signature, so old calls can be rejected outright (they're already harmless).
- No rate limiting on the public endpoints.
- No stemming in search (it's language neutral). Language specific search would need separate
  configurations or a search engine.
- Swagger UI is on everywhere, turn it off with `SPRINGDOC_API_DOCS_ENABLED=false` and
  `SPRINGDOC_SWAGGER_UI_ENABLED=false` if needed.
