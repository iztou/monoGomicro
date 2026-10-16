English | [简体中文](README.zh-CN.md)

# monoGomicro

[![Maven Central](https://img.shields.io/maven-central/v/io.github.iztou/monogomicro-parent.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.iztou/monogomicro-mcp)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-17%2B-blue)

An open-source reimplementation of IBM Mono2Micro: turns a Java monolith into
actionable microservice partitioning advice — interactively editable, with a
compilable service skeleton generated at the end.

Feed it a Java/Maven monolith; get back: microservice partition proposals
(interactively editable), shared-kernel detection, a compilable multi-module
Maven skeleton with cross-service calls rewritten to REST, and a migration
checklist for everything that needs a human.

![CLI demo: analyze → partition → generate (recorded on RuoYi 4.8.3)](docs/demo.gif)

## Feature parity with IBM Mono2Micro

| Mono2Micro capability | This project | Module |
|---|---|---|
| Static analysis | JavaParser symbol resolution (source + JDK + Maven dependency jars); imports, generic type args, field annotations, enums, @interfaces, class literals and varargs all covered | core |
| Runtime analysis | ByteBuddy java agent instrumentation; business use cases split by HTTP entry point, call chains rebuilt | agent |
| AI partitioning | Dual-graph fusion + Louvain community detection + simulated-annealing multi-objective refinement (communication cost / use-case span / size balance) | core |
| Graph view & editing | Local web console: D3 force-directed graph, drag-to-adjust, live metric recompute | web |
| Code refactoring | Partition → Maven multi-module services: cross-service injections rewritten to REST clients/controllers, shared DTO extraction, kernel dependency closure, MIGRATION.md | codegen |

## Architecture

```
monogomicro-core      parser / dependency graph / partitioning / metrics — zero-framework library
monogomicro-agent     runtime instrumentation agent (ByteBuddy, premain)
monogomicro-codegen   partition → microservice project generation (source rewriting)
monogomicro-cli       the m2m CLI (analyze / partition / trace / generate)
monogomicro-web       local console (thin Spring Boot REST + D3 single page)
```

## Install

Artifacts are on [Maven Central](https://central.sonatype.com/artifact/io.github.iztou/monogomicro-mcp) (`io.github.iztou`, 0.1.0+):

```bash
# Homebrew (macOS / Linuxbrew)
brew install iztou/tap/m2m        # CLI: m2m analyze / partition / trace / generate
brew install iztou/tap/m2m-mcp    # MCP server (stdio)

# jbang — no clone, no package step; jbang even provisions the JDK
jbang io.github.iztou:monogomicro-cli:0.2.0 analyze /path/to/monolith -p com.your.pkg -o m2m-out
```

```xml
<!-- or as a Maven dependency (core is a zero-framework library, embed it directly) -->
<dependency>
    <groupId>io.github.iztou</groupId>
    <artifactId>monogomicro-core</artifactId>
    <version>0.2.0</version>
</dependency>
```

Building from source: see Quick Start below.

## Quick Start

```bash
mvn -DskipTests package

# 1. Static analysis (Maven classpath resolved automatically)
java -jar monogomicro-cli/target/m2m.jar analyze /path/to/monolith -p com.your.pkg -o m2m-out

# 2. Partitioning (optionally fused with runtime traces)
java -jar monogomicro-cli/target/m2m.jar partition -a m2m-out/analysis.json -o m2m-out

# 3. (optional) Runtime capture: start your app with the agent
java -javaagent:monogomicro-agent/target/monogomicro-agent-0.2.0.jar=\
out=/tmp/trace.log,include=com.your.pkg.,entry=DispatcherServlet -jar app.jar
#    then re-partition with the trace:
java -jar monogomicro-cli/target/m2m.jar partition -a m2m-out/analysis.json -t /tmp/trace.log -o m2m-out

# 4. Generate the microservice projects (output dir passes mvn compile)
java -jar monogomicro-cli/target/m2m.jar generate \
  -a m2m-out/analysis.json -p m2m-out/partition.json \
  --project /path/to/monolith -o m2m-out/services

# 5. (optional) Visual console: http://localhost:8080
java -jar monogomicro-web/target/monogomicro-web.jar
```

## MCP mode (LLM in the loop)

`monogomicro-mcp` exposes the pipeline as an [MCP](https://modelcontextprotocol.io) server.
Default transport is stdio: Claude Desktop or any MCP client can do "analyze → summarize →
ask why a class landed here → move a class and watch the metrics → undo" conversationally:

```json
// Claude Desktop -> Settings -> Developer -> Edit Config (claude_desktop_config.json)
{
  "mcpServers": {
    "monogomicro": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/monogomicro-mcp/target/m2m-mcp.jar"]
    }
  }
}
```

For team/LAN sharing there is an HTTP transport (Streamable HTTP; all clients share one workspace state):

```bash
java -jar monogomicro-mcp/target/m2m-mcp.jar --http --port 8082   # binds 127.0.0.1 by default; POST /mcp
# client config: type "http" + url http://127.0.0.1:8082/mcp
```

Note: the Host-header allowlist (DNS-rebinding protection) only accepts the bind address and
loopback variants. For LAN sharing, bind your LAN IP explicitly (e.g. `--host 192.168.1.10`);
binding `0.0.0.0` will get LAN clients 403-rejected on Host mismatch.

15 tools: `analyze_project` / `load_workspace` / `ingest_trace` / `compute_partition` /
`generate_services` (dry-run by default) / `save_partition` (pipeline);
`get_partition_summary` / `list_classes` / `get_class_info` / `find_classes` / `get_metrics` /
`get_migration_items` (observation, progressive disclosure, ≤4KB per tool result);
`move_class` (returns metric delta) / `rename_service` / `undo` (editing).
Two resources — `m2m://analysis` and `m2m://partition` — let clients pull the big objects on demand.
Errors are structured (`{code, message, hint}`, with near-miss suggestions for unknown class/service ids);
writes are confined to the workspace root; `analyze_project` streams progress notifications
(pass `_meta.progressToken` and get an update every 50 source files).
Design doc: `docs/mcp-design.md` (Chinese). Contract tests — real subprocess stdio + HTTP,
full JSON-RPC round trips on both transports — run as part of `mvn verify`.

Try it visually with the official Inspector:

```bash
npx @modelcontextprotocol/inspector java -jar monogomicro-mcp/target/m2m-mcp.jar
# open the printed URL: walk the Tools list; check Resources for m2m://analysis
```

A real conversation (RuoYi, Claude Desktop):

> **Me**: how should I split this monolith?
> **AI**: `load_workspace`… 12 services, modularity 0.55, cross-service communication 19.9%,
>         shared kernel of 7 classes. `get_partition_summary`: job-service(16) / file-service(12) / …
> **Me**: why is SysUserServiceImpl in user-service?
> **AI**: `get_class_info` → SERVICE_IMPL; 8 of its 25 out-edges point into user-service
>         (`neighborConcentration`), and its strongest dependency SysUserMapper (weight 12) is in the same service.
> **Me**: try moving it to job-service
> **AI**: `move_class` → crossRatio 0.1994 → **0.2134**, modularity 0.5476 → **0.5379** (both worse).
>         Recommend undo — `undo` applied, metrics restored.

## Case study: RuoYi 4.8.3 (285 source files)

| Stage | Result |
|---|---|
| analyze | 290 classes / 980 weighted dependency edges / 92 dependency jars auto-resolved |
| partition | modularity Q=0.81, 12 services, ~15% cross-service communication |
| known boundaries recovered | ruoyi-quartz → job service (16/19 classes), ruoyi-generator → table service, system module split into user/role/post/dict vertical slices |
| generate | 12 services + shared common kernel / 16 RPC bridges / 13 injections rewritten / 21 mapper XMLs migrated |
| acceptance | **generated projects pass `mvn compile`**; 4 residual items listed in MIGRATION.md |

## Algorithm highlights

- **Edge weights**: inheritance 3 / field 2 / method call·parameter·annotation·constructor 1, with source line provenance throughout
- **Dual-graph fusion**: `α·static/max + β·dynamic/max` (0.5/0.5 by default), scaled ×1000 before rounding so tiny edges don't truncate to zero
- **Louvain**: full two-phase implementation (local moving + aggregation), hand-written
- **Annealing refinement**: objective = 0.5·cross-service communication ratio + 0.3·use-case span (Mono2Micro's core insight: the more services a use-case call chain crosses, the worse the latency) + 0.2·size imbalance
- **Shared kernel**: high-fan-in classes spanning ≥3 communities are peeled off automatically; generation then closes the kernel's dependencies and hoists cross-service shared classes (class-level dependencies between services are forbidden)
- **RPC rewrite rules**: field injection of a cross-service interface → mirrored RestTemplate client on the consumer + `@RestController` bridge on the provider; varargs/generics/multi-arg methods mirrored as-is

## Known limits (no silver bullets)

- Transactions/locks/reflection across service boundaries can't be preserved automatically — all listed in MIGRATION.md
- Client-side return values rely on JSON deserialization casts; add explicit DTOs for critical interfaces before production
- Runtime analysis quality depends on use-case coverage; pure static mode is the fallback
- Target stack is Spring Boot (the original used Open Liberty); dependency management must be explicit
  (root pom with `dependencyManagement`/BOM — the RuoYi style; monoliths using pure parent-inheritance
  dependency management will produce projects missing BOM versions, to be patched by hand)
- Running the generated services requires database/config migration (this tool guarantees
  compile-level correctness + the migration list; runtime correctness of the RPC bridge is
  guarded by codegen's failsafe integration test: generated projects install → both services
  started → bridge exercised over HTTP)

## Tests

`mvn test`: Louvain two-cluster recovery, single-community, empty graph; trace parsing
(single-thread use-case splitting, multi-thread interleaving).

## License

[Apache-2.0](LICENSE)
