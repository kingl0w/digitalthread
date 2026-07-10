# Digital Thread Graph

A Java microservices system that ingests fragmented public vehicle-lifecycle data from several
sources, resolves it into one governed Neo4j knowledge graph, and answers questions no single
source can: recall blast radius and failure root cause.

## Thesis
Many messy sources in, one coherent graph out. The value is the integration, not the data volume.
If the data arrived clean and unified there would be no problem to solve and no reason for the
services to exist. The sources disagree, overlap, and use different identities for the same
physical thing; reconciling them through a governed schema is the hard, senior work.

## Core model
Two lineage edges carry the whole design:
- SerializedUnit -[:BUILT_TO]-> Revision      design lineage (which design a unit was built to)
- SerializedUnit -[:COMPOSED_OF]-> SerializedUnit   physical lineage (the real assembly tree)
Plus: SerializedUnit -[:MADE_FROM]-> MaterialLot -[:SUPPLIED_BY]-> Supplier, and
SerializedUnit -[:INSTALLED_IN]-> Asset. FailureEvent and MaintenanceEvent hang off units/assets.

A design defect propagates along BUILT_TO. A material or process defect propagates along
COMPOSED_OF and MADE_FROM. Two defect classes, two traversal directions.

## The two money queries (everything justifies itself against these)
1. Blast radius: given a bad material lot, supplier, or revision, find every deployed asset that
   transitively contains an affected unit, at any depth.
2. Root cause: given a cluster of failures, walk back through the physical tree to the common
   upstream cause (same lot, same work center, same window, same superseded revision).

## Data sources
Real public data anchors the design and field ends; synthetic generation fills the private middle
that no public source exposes (as-built genealogy, material lots, work centers). Owning the middle
is what gives the money queries verifiable ground truth.
- FAA aircraft registry (ReleasableAircraft.zip): every US-registered aircraft with airframe
  serial number, model code, and engine model code. Real serialized assets and a real
  airframe-to-engine composition edge. The design spine.
- Airworthiness Directives via the Federal Register API: recall campaigns that name models and
  serial-number ranges. The blast-radius triggers.
- Service Difficulty Reports (sdrs.faa.gov): field failure reports with aircraft, engine, part,
  and component serial numbers, part numbers, JASC codes, and discrepancy text. The root-cause
  signal. No bulk API; acquisition drives the ASPX query form (viewstate postback, select all,
  download).
- Seed generator (synthetic): fleet, BOM, suppliers, lots, work orders, assembly trees, and a
  ground-truth defect manifest that serves as the test oracle.
Primary domain: general aviation, CESSNA fleet. Architecture is domain-agnostic and transfers to
automotive (NHTSA) or medical devices (openFDA GUDID, MAUDE). The acquisition source layer is
pluggable via the Source interface, so those drop in without touching the core.
History: the project started on NHTSA / International trucks. Pivoted to aerospace because the
per-vehicle recalls/complaints API deterministically times out on vPIC model codes (the two NHTSA
systems use different model vocabularies), so full coverage was impossible. NhtsaSource is kept
as a second Source implementation but is not registered.
FAA access notes: registry and SDRS sit behind a WAF that rejects non-browser User-Agents and
HEAD requests. Use GET with browser-like headers.

## Target stack
Java 21, Spring Boot, Neo4j. Spring Data Neo4j for domain CRUD; the bare driver / hand-written
Cypher for the traversal queries (the OGM mapper fights arbitrary-shape path results). GraphQL for
the read surface. Schema formalized as an ontology (OWL / SHACL) once the model stabilizes.

## Build order (do not reorder)
1. Core: define the schema, build the seed generator, prove BOTH money queries in raw Cypher
   against generated data with a ground-truth manifest. No services yet. This is the project.
2. Query service wrapping the money queries (demoable).
3. Event-driven ingestion + write service with idempotency and entity resolution. Kafka earns its
   place here. This is where the "many messy sources, one identity" work lives.
4. Hardening: bitemporality (validFrom/validTo/recordedAt on nodes and edges), SHACL validation,
   observability, Testcontainers integration tests against real Neo4j.
5. Demo scenario + README: seed a fleet, inject a bad lot, run blast radius and root cause, watch
   affected assets light up.

## Status (as of 2026-07-10, end of day)
Build order steps 1-4 are DONE and verified. Step 5: README is DONE (repo root). The demo plan
changed from a local script to a hosted public demo — see "Hosted demo" below; it is blocked on
Aura provisioning and is the pickup point.

Modules (each runs with `mvn -q compile exec:java` unless noted):
- digital-thread-acquisition: FAA crawl (registry zip, AD metadata + rule full text, SDR
  form-crawl). Idempotent; raw zone at digital-thread-acquisition/data/raw with _manifest.tsv.
- digital-thread-core: CoreApp (default main) = transform raw to canonical + seed generator +
  ground_truth.json. GraphApp (`-Dapp.main=com.aetnios.dt.core.GraphApp`) = SHACL-validate the
  zones (src/main/resources/shapes.ttl, Jena), wipe, load Neo4j, assert money queries +
  bitemporal coverage. `mvn test` = Testcontainers integration test (full pipeline against a
  throwaway Neo4j) + unit tests. Data zones: digital-thread-core/data/{canonical,seed}.
- digital-thread-query: Spring Boot GraphQL read surface (`mvn spring-boot:run`, port 8080,
  GraphiQL at /graphiql, actuator at /actuator/health|metrics). blastRadiusByLot / ByRevision /
  ByCampaign, rootCause, topCampaigns.
- digital-thread-ingest: step-3 write service (`mvn spring-boot:run`, port 8081, actuator with
  ingest.events.consumed / ingest.resolution.asset.hits|misses gauges); `--replay
  --ingest.consume=false` publishes canonical failure events to Kafka topic dt.failures with
  resolved ids stripped; the consumer re-resolves entities and MERGEs idempotently.
- docker-compose.yml at repo root: Neo4j 5 (neo4j/digitalthread) + single-node Kafka. Neo4j has
  no volume; rerun GraphApp after compose up to reload.

Step 4 hardening (2026-07-10): every node and edge carries recordedAt (transaction time,
ON CREATE so replays preserve first-seen); validFrom = domain time where a source has one
(FailureEvent.date, Campaign.publicationDate, Asset.airworthinessDate, WorkOrder.start; 83,863
nodes), validTo open; Dates normalizes the three source formats (ISO / YYYYMMDD / M/d/yyyy).
SHACL (1.25M triples, 0 violations) gates the load and provably fails on a missing required
property or an edge into the wrong node type. Note for Testcontainers: docker-java needs
api.version=1.44 (set via surefire systemPropertyVariables) or modern Docker daemons reject it.

Verified results: blast radius by lot 20/20, by revision 17/17, root cause LOT-00049 with 9/9
events (all exact-set asserted against ground_truth.json, rngSeed 42); real recall AD 2020-24046
reaches 6,393 registered aircraft via 74 AFFECTS edges parsed from rule text; Kafka replay
converges (176,826 nodes / 187,073 rels) and a duplicate replay changes nothing, with recordedAt
coverage staying total across consumer writes.

Interactive demo artifact (claude.ai, update by republishing the same file):
https://claude.ai/code/artifact/35e308e1-fd64-4f3d-a1d5-05ee60a0960b

## Hosted demo (in progress — the pickup point)
Decision: public always-on demo = Neo4j AuraDB Free + digital-thread-query on Fly.io. Only the
read surface is hosted; ingest/Kafka stay local. Graph fits Aura Free (176,826 nodes of the
200k cap). MUST stay separate from the user's other Aura Free instance a69394ca
("pharma-supply-graph", different project, same Downloads folder) — do NOT load into it; a
second free account was created for this project instead.

Already done and verified locally:
- Query service hardened for public exposure: RateLimitFilter (30 req/min per client,
  X-Forwarded-For aware), rootCause capped at 100 eventIds with a clean BAD_REQUEST error
  (rootCause return type made nullable in schema.graphqls so the cap error isn't followed by
  non-null noise).
- digital-thread-query/Dockerfile (multi-stage, build validated) and fly.toml (health check on
  /actuator/health, scale-to-zero, concurrency caps, actuator restricted to health via env).
- GraphApp wipe + bitemporal checks are scoped to :Node, so loading into a shared instance
  cannot touch foreign data (belt-and-braces even though we're not sharing).
- README section "Hosting the public demo" has the exact load + deploy commands.

Blocked on: Aura instance b28b1ca7 (name digital_thread, user neo4j, creds file
Neo4j-b28b1ca7-Created-2026-07-10.txt in the Windows Downloads folder) stuck in "Creating" —
its DNS record never appeared. Root cause: Aura provisioning incident on 2026-07-10 (status page
confirmed a third-party dependency failure ~17:18-17:51 UTC, exactly our window). Incident is
resolved but the instance hadn't recovered as of pausing.

To resume, in order:
1. Check console.neo4j.io (second account): if b28b1ca7 is Running, proceed. If still stuck in
   Creating, delete it and create a fresh free instance (should provision in 2-5 min now that
   the incident is over); a new Neo4j-*-Created-*.txt lands in Downloads.
2. Load Aura (from digital-thread-core; creds from the Downloads file — never commit them):
   mvn -q compile exec:java -Dapp.main=com.aetnios.dt.core.GraphApp \
     -Dneo4j.uri="neo4j+s://<id>.databases.neo4j.io" -Dneo4j.user=neo4j -Dneo4j.pass="<pw>"
   It runs SHACL + money queries + bitemporal checks against Aura itself; expect all PASS.
3. Deploy (from digital-thread-query; needs `fly auth login` first):
   fly launch --copy-config --no-deploy
   fly secrets set NEO4J_URI=... NEO4J_USER=neo4j NEO4J_PASSWORD=...
   fly deploy
4. Smoke-test the public URL: blastRadiusByLot(LOT-00049) = 20 assets, rootCause(SEED-F-0001..9)
   = LOT-00049 9/9, blastRadiusByCampaign(2020-24046) = 6,393; confirm 429 after 30 req/min.
5. Put the public GraphiQL link in the README, republish the demo artifact.

Next up after the hosted demo:
1. Deferred refinements: AD serial-range applicability (model-level only today), SDR grid pager
   (monthly windows assumed to fit one page), FailureEvent id collisions across SDR rows sharing
   an OperatorControlNumber, query-service tests, validTo supersession (nothing closes validity
   intervals yet — no source emits corrections). Grep `ponytail:` for the deliberate-shortcut
   ledger.

## Invariants
- Raw zone is immutable; acquisition never parses into the model.
- The seed generator MUST emit a ground-truth defect manifest; the money-query tests assert
  against it. Build this in from the first line, not as an afterthought.
- Entity resolution is the load-bearing skill: the same physical aircraft appears in the registry
  (N-number, serial, model code), in an AD (free-text model list, serial ranges), and in an SDR
  (make/model text, JASC component codes, part serials). One node must represent the same unit
  regardless of which feed mentioned it.
