# Accelerator for Amazon MCF and SAP ECC

Sample integration flows for connecting SAP ECC to Amazon Multi-Channel Fulfillment (MCF) using SAP Business Technology Platform (BTP) Integration Suite and [Selling Partner API (SP-API)](https://developer-docs.amazon.com/sp-api/).

## About Amazon Supply Chain Services

[Amazon Supply Chain Services](https://supplychain.amazon.com/) provides end-to-end supply chain solutions — from global shipping and bulk storage to fulfillment and last-mile delivery — enabling merchants to leverage Amazon's logistics network for orders across any sales channel. Key services include:

- **Multi-Channel Fulfillment (MCF)** — Fast, reliable fulfillment for orders from any ecommerce channel
- **Amazon Warehousing & Distribution (AWD)** — Upstream bulk inventory storage with auto-replenishment to fulfillment centers
- **Amazon Global Logistics (AGL)** — International shipping from manufacturers to AWD
- **Multi-Channel Distribution (MCD)** — Bulk inventory distribution to wholesalers and B2B partners

This repository is maintained by the Amazon Supply Chain Services (ASCS) Solutions Architecture team.

## Overview

This repository provides **standalone Amazon MCF integration flows for SAP ECC** using SP-API — a self-contained set of iFlows that connect SAP ECC directly to Amazon MCF.

Key characteristics:

- **MCF via SP-API** — All flows integrate with Amazon MCF using SP-API directly
- **RFC/BAPI connectivity** — Communicates with SAP ECC via RFC/BAPI calls through SAP Cloud Connector (not OData)
- **Self-contained** — No external base package required; import and configure in a single CPI package

## Architecture

```
┌─────────────┐     Cloud Connector      ┌─────────────────────┐       SP-API        ┌─────────────┐
│   SAP ECC   │◄──────(RFC/BAPI)─────────►│  SAP BTP Integration │◄────(REST/JSON)────►│  Amazon MCF │
│             │                           │       Suite (CPI)    │                     │  (SP-API)   │
└─────────────┘                           └─────────────────────┘                     └─────────────┘
```

- **Outbound (SAP → Amazon):** Deliveries created in SAP ECC are extracted via RFC/BAPI, transformed to SP-API format, and submitted to Amazon MCF as fulfillment orders. The Amazon fulfillment order ID is stamped back onto the ECC delivery document header to mark it as processed.
- **Inbound (Amazon → SAP):** Shipment and cancellation events are delivered by Amazon MCF to an **Amazon SQS** queue. A dedicated SQS listener iFlow polls the queue using the SAP BTP **Amazon Web Services (AWS) adapter** and hands each event to the event router, which routes it to the appropriate handler flow (Shipment or Cancel). That handler transforms the event and posts back to ECC via RFC/BAPI to update the existing delivery document — confirming picking, updating dates and tracking, posting Goods Issue, or zeroing out cancelled quantities.

## Integration Flows

| Flow | Direction | Description |
|------|-----------|-------------|
| Create Fulfillment Order in Amazon MCF From ECC | SAP → Amazon | Creates a fulfillment order in Amazon MCF from an ECC delivery document (fetches ship-to address, submits to SP-API, and stamps the order ID back on the delivery header) |
| Sync All Fulfillment Orders from SAP ECC to Amazon_MCF | SAP → Amazon | Batch replication of all eligible orders from ECC to Amazon MCF |
| Sync Selected Fulfillment Orders from SAP ECC to Amazon ECC | SAP → Amazon | Selective order replication based on filter criteria |
| Receive Delivery Notifications via SQS | Amazon → SAP | Polls the Amazon SQS queue using the SAP BTP AWS adapter and forwards each delivery notification to the event router (via ProcessDirect). Basic reference implementation — enhance as needed |
| Receive Delivery Event from Amazon MCF To ECC | Amazon → SAP | Event router, receives notifications from the SQS listener and routes each to the appropriate handler based on event type |
| Receive Package Delivery Fulfillment from Amazon MCF From ECC | Amazon → SAP | Processes shipment events, updates the delivery document, confirms picking, and posts Goods Issue in ECC |
| Receive Package Delivery Cancel from Amazon_MCF To ECC | Amazon → SAP | Receives and processes order cancellation events |

## Prerequisites

- SAP ECC 6.0 (EHP 7.5)
- SAP BTP Integration Suite (Cloud Integration capability)
- **SAP Cloud Connector** — Installed and configured on SAP BTP for RFC/BAPI access to SAP ECC
- Amazon Selling Partner API credentials (client ID, client secret, refresh token)

## Setup

The steps below are a high-level overview. For detailed, step-by-step configuration — including per-iFlow parameters, SAP Cloud Connector and RFC destination setup, credential aliases, and event delivery — see the **[Setup Guide](docs/Setup_Guide.md)**.

1. **Create a new package** in SAP BTP Integration Suite for the MCF-ECC flows.
2. **Import the iFlows** from `iFlows/` in this repository:
   - From within each iFlow folder, create a zip that includes hidden files (e.g., `.project`):
     - macOS/Linux: `cd <iFlow folder> && zip -r <FlowName>.zip .`
     - Windows (PowerShell): `cd <iFlow folder>; Compress-Archive -Path .\* -DestinationPath <FlowName>.zip`
   - Import the zip into your CPI package
3. **Configure SAP Cloud Connector:**
   - Add a mapping for your SAP ECC system (RFC destination)
   - Ensure the required BAPIs/RFCs are exposed (see [docs/MCF_SAP_ECC_Integration_Flows_Technical_Design.md](docs/MCF_SAP_ECC_Integration_Flows_Technical_Design.md) for the full list of RFC/BAPI calls per flow)
4. **Configure credentials** in SAP BTP Secure Store:
   - `Amazon_SP_REST_Credentials` — SP-API client ID and secret
   - `Amazon_SP_REST_Refresh_Token` — SP-API refresh token
   - ECC credentials are configured on the BTP RFC Destination (not in the Secure Store)
5. **Update `parameters.prop`** in each iFlow with your environment-specific values (RFC destination name, plant, shipping point, etc.)
6. **Event delivery** (inbound flows): Amazon MCF publishes shipment and cancellation notifications to an **Amazon SQS** queue. The **Receive Delivery Notifications via SQS** iFlow polls that queue using the SAP BTP **Amazon Web Services (AWS) adapter** and forwards each message to the event router. Configure the AWS adapter's queue, region, and credential aliases as described in the [Setup Guide](docs/Setup_Guide.md#step-6-event-delivery-setup-inbound-flows).

## Documentation

- **[Setup Guide](docs/Setup_Guide.md)** — Step-by-step configuration and deployment, including per-iFlow parameters, Cloud Connector and RFC destination setup, credential aliases, and event delivery.
- **[Technical Design](docs/MCF_SAP_ECC_Integration_Flows_Technical_Design.md)** — Flow architecture, RFC/BAPI calls per flow, and sequence diagrams.
- **[ECC Trigger Configuration Guide](docs/ECC_Trigger_Configuration_Guide.md)** — Optional ECC-side NACE output determination setup to trigger real-time order push.
- **[Testing & Validation](test/README.md)** — Offline JUnit test harness (no SAP or Amazon access needed) plus sample payloads validating the outbound create-order and inbound event flows.

## SP-API Version

This accelerator uses:

- **Fulfillment Outbound API v2026-07-04** — for outbound order creation (SAP → Amazon).
- **Fulfillment Outbound API 2020-07-01 notifications** (`FulfillmentOrderStatusNotification`) — for inbound Shipment (`EventType` = `Shipment`) and Cancel (`EventType` = `Order`) events (Amazon → SAP).

## Testing

An offline test harness is included under [`test/`](test/README.md). It validates the SP-API
**2026-07-04** transformation logic **without a SAP BTP / Cloud Integration runtime, without
SAP ECC, and without any Amazon connectivity** — no runtime, no RFC calls, no network.

> **Note:** The offline test harness under `test/` is provided for **experimental/development
> use only**, to validate transformation logic before deploying to a licensed SAP BTP Cloud
> Integration runtime. Users are expected to obtain a licensed **SAP BTP Cloud Integration**
> environment for production use.

The harness runs the **real** production Groovy transformation scripts from the `iFlows/` folders
against sample payloads, comparing the produced output (SP-API `createOrder` JSON for the outbound
flow; RFC/BAPI XML for the inbound event flows) to expected fixtures. Because those scripts import
the SAP CPI class `com.sap.gateway.ip.core.customdev.util.Message` — only available in a licensed
SAP BTP Cloud Integration runtime and not redistributable — the harness supplies a small original
re-implementation of just the API shape used, plus a `groovy.util.XmlSlurper` compatibility shim.
Only API names/signatures are reproduced; the implementations are original Amazon work, and **no
SAP code or libraries are included or redistributed** (see [NOTICE](NOTICE), "Test Harness").

Run it (JDK 17–21; Gradle 8.8 does not support JDK 24+):
```
cd test
export JAVA_HOME=/path/to/jdk-21
./gradlew test
```

See [`test/README.md`](test/README.md) for details and scope.

## Third-Party Attribution

This repository includes internal BTP processing routines (Groovy scripts, mapping patterns) derived from the [SAP API Business Hub Integration Recipes](https://github.com/SAP/apibusinesshub-integration-recipes/tree/master/Recipes/for/amazonmcfandbuywithprimeacceleratorsforsaps4hana), originally published by SAP SE under the Apache License 2.0. The ECC interface layer (RFC/BAPI integration) is original work by Amazon. See [NOTICE](NOTICE) and [MODIFICATIONS.md](MODIFICATIONS.md) for details.

## Disclaimer

This project is provided as sample code only. It is not intended for production use without thorough review, testing, and customization. The authors make no representations or warranties of any kind, whether express or implied. See the [LICENSE](LICENSE) for the full terms.

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
