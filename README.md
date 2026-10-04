# openvpp-demo

[简体中文](README.zh-CN.md) | **English**

> Companion example project for the CSDN article series *Building Virtual Power Plant Systems: From IoT Integration to Market Operations*.
> Scope: a minimal runnable virtual power plant system implementation, neither a toy demo nor production code.

The linked tutorials and reference documents are currently available in Chinese.

![Java](https://img.shields.io/badge/Java-11-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen)
![Maven](https://img.shields.io/badge/Maven-multi--module-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

<p align="center">
  <a href="https://blog.csdn.net/zuozewei/category_13211922.html">
    <img src="docs/images/openvpp-cover-en.png" alt="Cover for Building Virtual Power Plant Systems: From IoT Integration to Market Operations" width="800">
  </a>
</p>

## 📌 Project information

| Item | Description |
|------|-------------|
| Article series | [*Building Virtual Power Plant Systems: From IoT Integration to Market Operations*](https://blog.csdn.net/zuozewei/category_13211922.html) (CSDN, in Chinese) |
| Purpose | Teaching and demonstration project: runnable companion code for the articles, with modules mapped to article topics |
| Technology | Java 11 · Spring Boot 2.7 · multi-module Maven project (11 business modules) |
| Persistence | H2 file database for the standalone application (no external dependencies); MySQL + Redis + EMQX for the Docker setup |
| Build | `mvn -s settings-openvpp.xml` (public mirrors; no private repository required) |
| License | [Apache-2.0](LICENSE) |

> **About the examples:** All example data is fictional. Regional rules are fictional teaching examples and do not represent admission or settlement rules in any real region.

## 🖼️ Project overview

One end-to-end scenario connects the virtual power plant workflow. The standalone application persists data in an H2 file database and needs no external services:

```
Simulated telemetry → ingestion validation → data storage → capability assessment
  → resource aggregation → response task → command dispatch → execution verification
  → response quantity calculation → settlement allocation → bill lookup
```

- Device connectivity: MQTT/CoAP dual-protocol gateway, device authentication, retransmission after disconnection, and a multi-protocol connection map.
- Capability assessment: seven 44260 indicators and a GBDT training and inference pipeline (Python auxiliary service + Java main application).
- Resource aggregation: VPP unit grouping, admission thresholds (four 47241 indicators), and committable capacity.
- Dispatch execution: command chain, rules engine, MPC rolling optimization, target decomposition, and an assessment loop.
- Settlement and market: baseline determination, four settlement quantities, versioned dispute corrections, and example regional rules.
- Engineering details: unit tests cover idempotency, concurrent claiming, cache timing, and SQL dialect adaptation; the teaching figures can be recalculated.

## ✅ Module overview

| Module | Responsibility | Related articles |
|--------|----------------|------------------|
| `openvpp-common` | Shared response format and enums (resource types and scenarios), with no business dependencies | Articles 01–04 |
| `openvpp-resource` | Resource records, object models, device shadows, and ledgers | Articles 04, 07, 11 |
| `openvpp-assessment` | Capability assessment algorithms (seven 44260 indicators), assessment strategies, and the GBDT training and inference pipeline | Articles 02, 12, 13; delivery track article 47 |
| `openvpp-aggregator` | VPP units, aggregation engine, and admission thresholds (four 47241 indicators) | Articles 03, 14 |
| `openvpp-gateway` | MQTT/CoAP protocol ingestion | Article 06 |
| `openvpp-iot` | Device authentication, retransmission after disconnection, and a multi-protocol connection map | Articles 09, 10; delivery track article 48 |
| `openvpp-dispatch` | Command chain, rules engine, MPC, target decomposition, and the dispatch loop | Articles 15, 16; algorithms track article 33; delivery track article 46 |
| `openvpp-settlement` | Baseline calculation and settlement allocation | Articles 17, 21 |
| `openvpp-market` | Submission and bidding (simplified demonstration) | Articles 19, 20 |
| `openvpp-edge` | Edge-side cache and retransmission demo | Article 09 |
| `openvpp-app` | Standalone application entry point and end-to-end workflow orchestration | Articles 05, 19, 25 |

## 📂 Project structure

```
openvpp-demo/
├── README.md                  # English project home (this file)
├── README.zh-CN.md            # Chinese project home
├── README.en.md               # Link from the previous English README path
├── LICENSE                    # Apache-2.0
├── settings-openvpp.xml       # Maven settings with public mirrors
├── docker-compose.yml         # Application + MySQL + Redis + EMQX
├── docs/
│   ├── README.md              # Documentation index (Chinese)
│   ├── snapshots.md           # Article tag reference (Chinese)
│   ├── tutorials/             # Companion tutorials 01–07 (Chinese)
│   └── case-study/            # End-to-end case calculations (Chinese)
├── tools/
│   ├── mqtt-burst.sh          # MQTT uplink burst test script
│   └── ai/                    # Dependency-free Python demos (GBDT/quantization/RAG) and sample data
├── openvpp-common|gateway|iot|resource|assessment|aggregator|dispatch|settlement|market|edge
└── openvpp-app/               # Standalone application and workflow orchestration
```

## 🚀 Quick start

```bash
mvn -s settings-openvpp.xml install -DskipTests
cd openvpp-app && mvn -s ../settings-openvpp.xml spring-boot:run

# Verify
curl http://127.0.0.1:8080/api/v1/system/ping
# {"code":0,"message":"success","data":{"service":"openvpp-demo","status":"UP",...}}
```

The H2 file database and local gateway simulation need no external services and can run offline. See [Tutorial 01](docs/tutorials/01-quick-start.md) for details.

## 📚 Companion tutorials

| Tutorial | Related article | Summary |
|----------|-----------------|---------|
| [01 Quick start without external services](docs/tutorials/01-quick-start.md) | General | Build, start, and health check; works offline |
| [02 Industrial park demand-response case](docs/tutorials/02-park-demand-response.md) | Article 19 | Workflow across 11 modules: assessment → aggregation → dispatch → settlement, including idempotency, concurrency, and dispute corrections |
| [03 One-command Docker Compose setup](docs/tutorials/03-docker-compose.md) | Article 25 | Four-container setup with the application, MySQL, Redis, and EMQX, plus real-environment regression checks |
| [04 Gateway loopback test](docs/tutorials/04-gateway-loopback.md) | Article 06 | Verify the MQTT/CoAP ingestion path |
| [05 AI toolkit (GBDT/quantization/RAG)](docs/tutorials/05-ai-toolkit.md) | Articles 29/30/47 | Three dependency-free Python demos and Java-side integration |
| [06 Time-series database write benchmark](docs/tutorials/06-tsdb-benchmark.md) | Time-series storage selection | Compare TDengine and ClickHouse under the same load |
| [07 MQTT uplink burst test](docs/tutorials/07-mqtt-burst.md) | Article 18 | Use the bulk uplink test script and interpret its output |

More documentation (in Chinese): [Documentation index](docs/README.md) · [Article tag reference](docs/snapshots.md) · [Case calculation notes](docs/case-study/park-demo.md) · [Article series on CSDN](https://blog.csdn.net/zuozewei/category_13211922.html)

## 🧪 Test scope

- Regular regression suite: 31 test classes / 205 test cases / 203 executed / 2 live tests skipped by default (as of commit 7e54828, 2026-10-04). The TSDB benchmark and GBDT auxiliary-service integration require external environments; see Tutorials 06 and 05.
- The public MQTT loopback uses a public broker. It **does not automatically skip offline** and will fail an assertion when disconnected; exclude that case separately when offline (Tutorial 04).
- `MySqlComposeIT` contains 4 real MySQL + Redis integration checks and requires the Compose services (Tutorial 03).
- The recommended reader entry point is the frozen `part1-cognition-r5` tag. See the [article tag reference](docs/snapshots.md) for differences from earlier snapshots.

## ⚠️ Notes

1. **Gateway mode:** The default `local` mode simulates traffic without connecting to external messaging services. For real MQTT/CoAP ingestion, explicitly set `openvpp.gateway.mode=remote` and configure `openvpp.mqtt.broker`. The project provides no default external address.
2. **No teaching-database migrations:** After schema changes, delete `~/.openvpp/openvpp-db*` before restarting.
3. **Live/external-dependency tests:** These are skipped by default or require external services; they do not affect a regular build. See the relevant tutorials.
4. **Maven mirror:** Always pass `-s settings-openvpp.xml` to use the public mirrors when global mirrors are unavailable.
5. **Environment data:** This repository includes no environment addresses or credentials. Inject connection details for your own environment through system properties or environment variables (see Tutorial 06).

## 📄 License

[Apache-2.0](LICENSE)
