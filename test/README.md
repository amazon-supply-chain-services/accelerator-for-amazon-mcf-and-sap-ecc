# Testing & Validation

This folder contains an **offline test harness** and sample payloads for validating the MCF-ECC integration flows.

**The tests run entirely offline** — no SAP BTP / CPI runtime, no SAP ECC, no Amazon SP-API, and no network calls to those systems. They exercise the *actual* production Groovy transformation scripts from the iFlow folders against sample inputs and compare the produced output (SP-API JSON for outbound, RFC/BAPI XML for inbound) to expected fixtures.

> You do **not** need access to Amazon or SAP to run these tests.

> For a summary of what each test validates and the last verified results, see
> [TEST_RESULTS.md](TEST_RESULTS.md).

---

## What is tested

| Direction | Flow | Input sample | Produced & validated |
|-----------|------|--------------|----------------------|
| **Outbound** (SAP → Amazon) | Create Fulfillment Order | `samples/SampleDeliveryGetList.xml` + `samples/SampleAddressResponse.xml` | SP-API `createOrder` JSON (v2026-07-04) → `expected/expected_createOrder.json` |
| **Inbound** (Amazon → SAP) | Shipment (`EventType` = `Shipment`) | `samples/shipment.json` | `BAPI_DELIVERY_GETLIST`, picking `WS_DELIVERY_UPDATE`, and Goods Issue `WS_DELIVERY_UPDATE` RFC XML |
| **Inbound** (Amazon → SAP) | Cancel (`EventType` = `Order`) | `samples/cancel.json` | Zero-qty `WS_DELIVERY_UPDATE` RFC XML → `expected/expected_cancel_ws_delivery_update.xml` |
| **Outbound batch** (SAP → Amazon) | Sync All Fulfillment Orders | `samples/SampleDeliverySearchResponse.xml` + `samples/SampleReadText_*.xml` | `BAPI_DELIVERY_GETLIST` search request, parsed delivery list, loop iteration, and `RFC_READ_TEXT` idempotency (skip vs. per-delivery `BAPI_DELIVERY_GETLIST`) |

The inbound samples use the **Fulfillment Outbound API 2020-07-01 `FulfillmentOrderStatusNotification`** format — a `FulfillmentOrderStatusNotification` wrapper with `EventType` (`Shipment` or `Order`), `SellerFulfillmentOrderId` (`<SalesOrder>.<DeliveryDoc>`), `FulfillmentOrderStatus`, and (for shipments) `FulfillmentShipment.FulfillmentShipmentPackages[]` carrying `CarrierCode` / `TrackingNumber`. The notification carries **no line items or quantities**, so pick/Goods-Issue quantities are taken from the ECC `BAPI_DELIVERY_GETLIST` response.

### Negative / edge cases covered

- **Partial shipment is rejected** — a Shipment notification with `FulfillmentOrderStatus = COMPLETE_PARTIAL` throws in `processData`; no GETLIST, picking, or Goods Issue (`shipment_partial.json`).
- **In-progress (multiple) shipment is rejected** — a Shipment notification with `FulfillmentOrderStatus = PROCESSING` throws (`shipment_processing.json`).
- **Partial cancellation is rejected** — a Cancel notification with `FulfillmentOrderStatus = COMPLETE_PARTIAL` throws (`cancel_partial.json`).
- **Already-shipped cancel is rejected** — a cancel against a delivery that is already processed (`GBSTK != A`) throws an exception (`SampleDeliveryStatus_Processed.xml`).
- **Sync All skips already-processed deliveries** — a delivery whose header text already contains a marker (`RFC_READ_TEXT` returns a non-empty `TDLINE`) is skipped rather than re-sent (`SampleReadText_Processed.xml`); deliveries with empty text proceed to a per-delivery fetch (`SampleReadText_NotProcessed.xml`).

---

## How it works

The production transformation logic lives inside the iFlow folders (e.g.
`iFlows/Create Fulfillment Order in Amazon MCF From ECC/src/main/resources/script/*.groovy`).
These scripts import the SAP CPI class `com.sap.gateway.ip.core.customdev.util.Message`,
which only exists inside the SAP BTP Cloud Integration runtime and is **not**
redistributable.

To run the scripts offline, the harness provides a small **stub** of that class
(`src/test/groovy/com/sap/gateway/ip/core/customdev/util/Message.groovy`) that
implements only the methods the accelerator uses (`getBody`, `setBody`,
`getProperty`, `setProperty`, `getProperties`, `setHeader`). The Gradle build
loads the real iFlow scripts at test time and runs them against the stub — so
the tests exercise the genuine production logic, not a copy.

For inbound flows that normally read a live `BAPI_DELIVERY_GETLIST` response
from ECC, the harness supplies a local mock response fixture so the downstream
validation and RFC-building steps can run without any RFC call.

---

## Prerequisites

- **JDK 17–21** (Java 21 recommended). Set `JAVA_HOME` accordingly.
- **Gradle is optional** — a Gradle wrapper (`./gradlew`) is included, so you do
  not need to install Gradle separately. (If you prefer, Gradle 8.x also works.)
- Internet access is required **only the first time**, so Gradle can download
  Groovy and JUnit from Maven Central. After that it runs fully offline.

> These are standard Java build tools. No SAP or Amazon SDKs, credentials, or
> connectivity are required.

---

## Running the tests

From this `test/` directory:

```bash
# Using the Gradle wrapper (if gradlew is present):
./gradlew test

# Or with a local Gradle install:
gradle test
```

A successful run reports all tests passing. Test reports are written to
`build/reports/tests/test/index.html`.

To run a single test class:

```bash
./gradlew test --tests "com.amazon.mcf.ecc.test.CreateOrderOutboundTest"
```

---

## Folder layout

```
test/
├── gradlew / gradlew.bat   # Gradle wrapper — run tests without installing Gradle
├── gradle/wrapper/         # Wrapper jar + properties (Gradle 8.8)
├── build.gradle            # Self-contained build (Groovy + JUnit 5)
├── settings.gradle
├── README.md               # This file
├── samples/                # Input payloads + mock RFC responses
│   ├── SampleDeliveryGetList.xml              # BAPI_DELIVERY_GETLIST (outbound input to Create Order script)
│   ├── SampleAddressResponse.xml              # BAPISDORDER address (outbound phase 2 input)
│   ├── SampleCreateOrder.xml                  # Full SOAP DeliveryReplication (Sync Selected endpoint input; end-to-end reference)
│   ├── shipment.json                # shipment event (EventType Shipment, Complete)
│   ├── shipment_partial.json        # shipment event, COMPLETE_PARTIAL (rejected)
│   ├── shipment_processing.json     # shipment event, PROCESSING (rejected)
│   ├── cancel.json                  # cancel event (EventType Order, Cancelled)
│   ├── cancel_partial.json          # cancel event, COMPLETE_PARTIAL (rejected)
│   ├── SampleShipmentDelivery_Match.xml       # GETLIST response, delivery 0080018897 (lines 10 & 20)
│   ├── SampleDeliveryStatus_NotProcessed.xml  # GETLIST response, delivery 0080018887 (GBSTK=A)
│   ├── SampleDeliveryStatus_Processed.xml     # GETLIST response, delivery 0080018887 (GBSTK=B, already processed)
│   ├── SampleDeliverySearchRequest.xml        # Sync All multi-delivery GETLIST request (ship point + date range + picking-not-started)
│   ├── SampleDeliverySearchResponse.xml       # Sync All multi-delivery GETLIST response (3 real deliveries; for future Sync All tests)
│   ├── SampleReadText_Processed.xml           # RFC_READ_TEXT response with marker text (Sync All -> skip)
│   └── SampleReadText_NotProcessed.xml        # RFC_READ_TEXT response with empty text (Sync All -> process)
├── expected/               # Expected transformation output for comparison
│   ├── expected_createOrder.json               # outbound SP-API createOrder JSON
│   ├── expected_cancel_ws_delivery_update.xml  # cancel zero-qty WS_DELIVERY_UPDATE
│   ├── expected_shipment_picking.xml           # shipment picking WS_DELIVERY_UPDATE (date masked)
│   ├── expected_shipment_goods_issue.xml       # shipment Goods Issue WS_DELIVERY_UPDATE (date masked)
│   └── expected_shipment_picking.xml           # shipment picking WS_DELIVERY_UPDATE (date masked)
└── src/test/groovy/
    ├── com/sap/gateway/ip/core/customdev/util/Message.groovy   # offline CPI Message stub
    ├── groovy/util/XmlSlurper.groovy                           # compat shim (see note below)
    └── com/amazon/mcf/ecc/test/
        ├── TestSupport.groovy               # loads production scripts, reads fixtures
        ├── CreateOrderOutboundTest.groovy   # outbound: SAP delivery -> SP-API JSON
        ├── ShipmentEventInboundTest.groovy  # inbound: shipment event -> RFC XML
        ├── CancelEventInboundTest.groovy    # inbound: cancel event -> RFC XML
        └── SyncAllOutboundTest.groovy       # outbound batch: Sync All delivery search + idempotency
```

> **Groovy version note:** SAP CPI runs Groovy 2.4, where `XmlSlurper` is in the
> `groovy.util` package. The production scripts `import groovy.util.XmlSlurper`.
> Modern Groovy (4.x, used by these tests) moved it to `groovy.xml.XmlSlurper`,
> so the test source set includes a tiny `groovy.util.XmlSlurper` compatibility
> shim that delegates to the real class. This lets the **unmodified** production
> scripts compile and run in the harness; it does not affect the production code
> or its behavior on CPI.

---

## Updating the tests

- **New sample?** Drop it in `samples/` and reference it from a test.
- **Transformation logic changed?** The tests load the live iFlow scripts, so they
  automatically exercise the new logic. Update the `expected/` fixtures if the
  output legitimately changed.
- **RFC payloads with timestamps** (picking/Goods-Issue dates) embed the current
  date/time. Before comparing them to an `expected/` fixture, the test masks those
  runtime fields via `TestSupport.maskTimestamps(...)` (e.g. `<KODAT>#DATE#</KODAT>`,
  `<WADAT_IST>#DATE#</WADAT_IST>`). The fixtures use the same placeholder tokens,
  so the comparison is stable across runs.

---

## Notes

- **Fixture provenance.** The Amazon event inputs (`shipment_*.json`, `cancel_*.json`)
  and the outbound `SampleCreateOrder.xml` are representative
  SP-API / DeliveryReplication payloads. The SAP RFC/BAPI **response** fixtures
  (`SampleShipmentDelivery_*.xml`, `SampleDeliveryStatus_*.xml`,
  `SampleDeliveryGetList.xml`, `SampleDeliverySearchResponse.xml`, `SampleAddressResponse.xml`)
  use real field names and values captured from an SAP ECC dev system
  (`BAPI_DELIVERY_GETLIST`, `BAPISDORDER_GETDETAILEDLIST`), trimmed to the subset
  of fields the scripts consume. They are **representative test data, not a live
  system contract** — if your ECC returns a different structure, update the
  fixtures (and any affected `expected/` output) to match.
- These tests validate the **transformation logic** (the mapping from SAP delivery
  data to SP-API JSON, and from Amazon events to RFC/BAPI XML). They do not, and
  cannot, validate live connectivity to SAP or Amazon.
- For end-to-end validation against a real CPI tenant, deploy the iFlows and post
  the `samples/` payloads to the flow endpoints (see the
  [Setup Guide](../docs/Setup_Guide.md) and
  [Technical Design](../docs/MCF_SAP_ECC_Integration_Flows_Technical_Design.md)).
