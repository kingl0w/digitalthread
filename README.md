# Digital Thread Graph

Many messy sources in, one coherent graph out.

**Live demo:** [https://digital-thread-query.onrender.com/graphiql](https://digital-thread-query.onrender.com/graphiql)
— GraphiQL over the real graph (176,826 nodes). Free-tier hosting; first request after a deploy
may take a minute.

This is a Java microservices system that ingests fragmented public aircraft-lifecycle data —
the FAA aircraft registry, Airworthiness Directives, Service Difficulty Reports — resolves it
into a single governed Neo4j knowledge graph, and answers two questions no single source can:

1. **Blast radius** — given a bad material lot, supplier, or design revision, find every
   deployed aircraft that transitively contains an affected part, at any depth.
2. **Root cause** — given a cluster of field failures, walk back through the physical assembly
   tree to the common upstream cause: same lot, same work center, same superseded revision.

The value is the integration, not the data volume. The sources disagree, overlap, and use
different identities for the same physical thing; reconciling them through a governed schema is
the point of the exercise.

## The model

Two lineage edges carry the whole design:

```
SerializedUnit -[:BUILT_TO]->    Revision          design lineage (which design a unit was built to)
SerializedUnit -[:COMPOSED_OF]-> SerializedUnit    physical lineage (the real assembly tree)
```

Plus the supporting cast:

```
SerializedUnit -[:MADE_FROM]->  MaterialLot -[:SUPPLIED_BY]-> Supplier
SerializedUnit -[:INSTALLED_IN]-> Asset
SerializedUnit -[:PRODUCED_BY]->  WorkOrder
Revision       -[:SUPERSEDED_BY]-> Revision
Campaign       -[:AFFECTS]->      Revision
FailureEvent   -[:ON_UNIT]->      SerializedUnit,  -[:ON_ASSET]-> Asset
```

A **design defect** propagates along `BUILT_TO`. A **material or process defect** propagates
along `COMPOSED_OF` and `MADE_FROM`. Two defect classes, two traversal directions — that's the
whole trick.

Example: one bad lot, two levels deep in an assembly, surfacing as a registered aircraft:

```
N1088U (Asset) <-INSTALLED_IN- airframe unit -COMPOSED_OF-> subassembly
    -COMPOSED_OF-> component -MADE_FROM-> LOT-00049 -SUPPLIED_BY-> SUP-11
```

## Data sources

Real public data anchors the design and field ends; synthetic generation fills the private
middle no public source exposes. Owning the middle is what gives the money queries verifiable
ground truth.

| Source | What it provides | Role |
|---|---|---|
| FAA aircraft registry (ReleasableAircraft.zip) | Every US-registered aircraft: airframe serial, model code, engine model code | Real serialized assets + a real airframe→engine composition edge. The design spine. |
| Airworthiness Directives (Federal Register API) | Recall campaigns naming models and serial ranges | The blast-radius triggers |
| Service Difficulty Reports (sdrs.faa.gov) | Field failure reports with aircraft/engine/part serials, JASC codes, discrepancy text | The root-cause signal |
| Seed generator (synthetic) | Fleet BOMs, suppliers, material lots, work orders, assembly trees | The as-built genealogy — plus `ground_truth.json`, the test oracle |

Primary domain: general aviation, CESSNA fleet. The `Source` interface makes the acquisition
layer pluggable — automotive (NHTSA) or medical devices (openFDA GUDID/MAUDE) drop in without
touching the core. (`NhtsaSource` exists but is unregistered: NHTSA's per-vehicle recall API
times out deterministically on vPIC model codes, making full coverage impossible. Hence the
aerospace pivot.)

## Architecture

Four modules, one per pipeline stage. Data flows through immutable zones:

```
FAA / Federal Register
        │  crawl (idempotent, manifest-tracked)
        ▼
digital-thread-acquisition ──► data/raw            (immutable, never parsed into the model)
        │  transform + seed
        ▼
digital-thread-core        ──► data/canonical + data/seed + ground_truth.json
        │  SHACL-validate, load, assert
        ▼
     Neo4j 5  ◄── digital-thread-ingest (Kafka consumer: entity resolution, idempotent MERGE)
        │
        ▼
digital-thread-query        GraphQL read surface (the money queries)
```

| Module | What it does | Run with |
|---|---|---|
| `digital-thread-acquisition` | FAA crawl: registry zip, AD metadata + rule full text, SDR form-crawl. Raw zone with `_manifest.tsv` | `mvn -q compile exec:java` |
| `digital-thread-core` | `CoreApp`: raw → canonical + seed generation. `GraphApp`: SHACL-validate, wipe, load Neo4j, assert money queries against ground truth | `mvn -q compile exec:java` (CoreApp) / add `-Dapp.main=com.aetnios.dt.core.GraphApp` |
| `digital-thread-query` | GraphQL read surface: blast radius, root cause, top campaigns | `mvn spring-boot:run` → port 8080 |
| `digital-thread-ingest` | Kafka write service: re-resolves entity identity from raw fields, MERGEs idempotently | `mvn spring-boot:run` → port 8081 |

## Quickstart

Prereqs: Java 21, Maven, Docker.

```bash
# 1. Infrastructure: Neo4j 5 + single-node Kafka
docker compose up -d

# 2. Acquire the public data (idempotent; re-runs skip completed work)
cd digital-thread-acquisition && mvn -q compile exec:java && cd ..

# 3. Transform to canonical + generate the synthetic middle + ground truth
cd digital-thread-core && mvn -q compile exec:java

# 4. Validate (SHACL), load Neo4j, and prove the money queries
mvn -q compile exec:java -Dapp.main=com.aetnios.dt.core.GraphApp && cd ..

# 5. Serve the read surface
cd digital-thread-query && mvn spring-boot:run
```

Step 4 ends with the self-check — this is the system proving itself against the ground-truth
manifest the seed generator emitted:

```
PASS SHACL: 1246986 triples validated, 0 violations
PASS blast radius (bad lot LOT-00049): 20 assets found, 20 expected
PASS blast radius (bad revision REV:SYN:...:EMPENNAGE:PRIMARY:A): 17 assets found, 17 expected
PASS root cause: cluster of 9 -> [LOT-00049 (9)], expected LOT-00049
PASS bitemporality: 0 nodes / 0 rels missing recordedAt, 83863 nodes carry validFrom
MONEY QUERIES: BOTH PASS
```

Neo4j has no volume by design (the graph is a projection of the data zones, rebuildable in
minutes); rerun GraphApp after `docker compose up`.

## Asking questions

GraphiQL lives at `http://localhost:8080/graphiql`.

**Blast radius** — which aircraft are flying around with parts from a bad lot?

```graphql
{ blastRadiusByLot(lotId: "LOT-00049") { id nNumber yearMfr } }
```

**Root cause** — nine failure events, one common upstream lot:

```graphql
{ rootCause(eventIds: ["SEED-F-0001", "SEED-F-0002", "SEED-F-0003", "SEED-F-0004",
                       "SEED-F-0005", "SEED-F-0006", "SEED-F-0007", "SEED-F-0008",
                       "SEED-F-0009"]) { lotId supplierId hits } }
```

**Real recalls** — actual Airworthiness Directives resolved against the actual registry;
AD 2020-24046 reaches 6,393 registered aircraft:

```graphql
{ blastRadiusByCampaign(campaignId: "2020-24046") { id nNumber } }
{ topCampaigns(limit: 5) { campaignId title designs fleet } }
```

Or browse visually in Neo4j Browser (`http://localhost:7474`, `neo4j`/`digitalthread`):

```cypher
MATCH p=(a:Asset)<-[:INSTALLED_IN]-(:SerializedUnit)-[:COMPOSED_OF*0..5]->()
        -[:MADE_FROM]->(:MaterialLot {id:'LOT-00049'})
RETURN p LIMIT 25
```

## Event-driven ingestion

The write path is where "many messy sources, one identity" lives. The replay publisher strips
resolved ids from canonical failure events and publishes raw identity fields (N-number, engine
serial, part serial) to Kafka; the consumer re-resolves them against the graph and MERGEs on
deterministic ids:

```bash
cd digital-thread-ingest
mvn spring-boot:run                                     # consumer
mvn spring-boot:run -Dspring-boot.run.arguments="--replay --ingest.consume=false --server.port=0"
```

Replay converges to the exact baseline; a duplicate replay changes nothing (176,826 nodes /
187,073 rels before and after). Resolution quality is observable live at
`http://localhost:8081/actuator/metrics/ingest.resolution.asset.hits` (and `.misses`).

## Hardening

- **Bitemporality** — every node and edge carries `recordedAt` (transaction time, stamped
  `ON CREATE` so replays preserve first-seen); nodes carry `validFrom` (domain time) where a
  source has one. Superseded revisions get `validTo` closed by their successor's `validFrom`;
  other labels stay open until a source emits corrections.
- **AD serial ranges** — where a rule's applicability text carries "serial numbers X through Y",
  the range envelope rides on the `AFFECTS` edge and campaign blast radius narrows to units in
  range; ADs without ranges stay model-level.
- **SHACL** — the schema is formalized in
  [`shapes.ttl`](digital-thread-core/src/main/resources/shapes.ttl): required properties per
  label, endpoint types for all eight edges. Validation gates every load; a dangling edge or an
  edge into the wrong node type fails before Neo4j is touched.
- **Tests** — `mvn test` in `digital-thread-core` runs the full pipeline (SHACL → load → money
  queries → bitemporal coverage) against a throwaway Neo4j via Testcontainers; `mvn test` in
  `digital-thread-query` covers schema/controller wiring, the rootCause id cap, and the rate
  limiter against a mocked driver.
- **Observability** — Spring Actuator health/metrics on both services; Micrometer gauges for
  ingest entity-resolution quality.

## Hosting the public demo

Only the read surface is hosted; ingest/Kafka stay local. The graph lives in Neo4j AuraDB Free
(fits the 200k-node cap at 176,826), and the query service runs on Render's free tier from
[`digital-thread-query/Dockerfile`](digital-thread-query/Dockerfile), declared in
[`render.yaml`](render.yaml). (A [`fly.toml`](digital-thread-query/fly.toml) is kept for
deploying to Fly.io instead.)

```bash
# 1. Create a free instance at console.neo4j.io, then load it (a few minutes over the wire).
#    The loader runs SHACL, both money queries, and the bitemporal checks against the target:
cd digital-thread-core
mvn -q compile exec:java -Dapp.main=com.aetnios.dt.core.GraphApp \
  -Dneo4j.uri="neo4j+s://<id>.databases.neo4j.io" -Dneo4j.user=neo4j -Dneo4j.pass="<password>"

# 2. Deploy the query service: at dashboard.render.com choose New > Blueprint and connect this
#    repo — render.yaml declares the service; enter NEO4J_URI / NEO4J_USER / NEO4J_PASSWORD
#    when prompted. Deploys re-run on every push.
```

Render's free tier sleeps after 15 idle minutes, but grants 750 instance-hours a month — enough
to run 24/7 — so a scheduled GitHub Action
([`.github/workflows/keep-warm.yml`](.github/workflows/keep-warm.yml)) pings the health endpoint
every 10 minutes to keep the JVM warm.

Abuse guards baked in: per-client rate limit (30 req/min), `rootCause` capped at 100 event ids,
and actuator restricted to `/actuator/health` in the deployed config. The Aura credentials live
only in Render environment variables; the database itself is never publicly reachable.

## Invariants

- The raw zone is immutable; acquisition never parses into the model.
- The seed generator MUST emit a ground-truth defect manifest; the money queries assert against
  it. Correct-looking is not correct.
- Entity resolution is the load-bearing skill: the same physical aircraft appears in the
  registry (N-number, serial, model code), in an AD (free-text model list), and in an SDR
  (make/model text, part serials). One node, regardless of which feed mentioned it.

Deliberate shortcuts are tracked inline — `grep -rn "ponytail:" --include="*.java"` for the
ledger (serial ranges are envelopes rather than exact lists, the in-memory rate limiter is
single-instance, formerly-split SDR months re-query once per crawl).
