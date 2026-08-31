# Setup Guide — Accelerator for Amazon MCF and SAP ECC

This guide walks you through importing, configuring, and deploying the Amazon MCF integration flows on SAP BTP Integration Suite (Cloud Integration).  

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Step 1: Configure SAP Cloud Connector](#step-1-configure-sap-cloud-connector)
- [Step 2: Create a Package in SAP BTP Integration Suite](#step-2-create-a-package-in-sap-btp-integration-suite)
- [Step 3: Import the iFlows](#step-3-import-the-iflows)
- [Step 4: Configure Credentials in Secure Store](#step-4-configure-credentials-in-secure-store)
- [Step 5: Configure iFlow Parameters](#step-5-configure-iflow-parameters)
  - [Common Configuration — SAP ECC Adapter](#common-configuration--sap-ecc-adapter)
  - [iFlow: Sync All Fulfillment Orders from SAP ECC to Amazon MCF](#iflow-sync-all-fulfillment-orders-from-sap-ecc-to-amazon-mcf)
  - [iFlow: Sync Selected Fulfillment Orders from SAP ECC to Amazon MCF](#iflow-sync-selected-fulfillment-orders-from-sap-ecc-to-amazon-mcf)
  - [iFlow: Create Fulfillment Order in Amazon MCF From ECC](#iflow-create-fulfillment-order-in-amazon-mcf-from-ecc)
  - [iFlow: Receive Delivery Event from Amazon MCF To ECC](#iflow-receive-delivery-event-from-amazon-mcf-to-ecc)
  - [iFlow: Receive Package Delivery Fulfillment from Amazon MCF To ECC](#iflow-receive-package-delivery-fulfillment-from-amazon-mcf-to-ecc)
  - [iFlow: Receive Package Delivery Cancel from Amazon MCF To ECC](#iflow-receive-package-delivery-cancel-from-amazon-mcf-to-ecc)
  - [iFlow: Receive Delivery Notifications via SQS](#iflow-receive-delivery-notifications-via-sqs)
- [Step 6: Event Delivery Setup (Inbound Flows)](#step-6-event-delivery-setup-inbound-flows)
- [Step 7: ECC-Side Trigger (Outbound — Optional)](#step-7-ecc-side-trigger-outbound--optional)
- [Step 8: Deploy and Verify](#step-8-deploy-and-verify)

---

## Prerequisites

| # | Requirement | Details |
|---|-------------|---------|
| 1 | **SAP ECC 6.0 (EHP 7.5)** | Supported on SAP ECC 6.0, enhancement package 7.5. No ABAP customizations required for the base flows. |
| 2 | **SAP BTP Integration Suite** | Cloud Integration (CPI) capability activated on your SAP BTP sub-account. |
| 3 | **SAP Cloud Connector** | Installed, connected to your SAP BTP sub-account, and configured for RFC access to ECC. |
| 4 | **Amazon Selling Partner API credentials** | Client ID, Client Secret, and Refresh Token from Amazon Seller Central (SP-API app registration). |
| 5 | **RFC/BAPI availability in ECC** | The following function modules must be accessible via the Cloud Connector RFC destination: `BAPI_DELIVERY_GETLIST`, `BAPISDORDER_GETDETAILEDLIST`, `WS_DELIVERY_UPDATE`, `BAPI_OUTB_DELIVERY_CHANGE`, `RFC_SAVE_TEXT`, `RFC_READ_TEXT`. |
| 6 | **Master data alignment** | Material numbers (`MATNR`) in SAP ECC must match the `amazonSku` values in your Amazon Seller Central catalog. |

---

## Step 1: Configure SAP Cloud Connector and BTP RFC Destination

All ECC communication uses RFC adapters. The RFC adapter connects via a **BTP Destination** that routes through SAP Cloud Connector to your on-premise ECC system.

### 1.1 SAP Cloud Connector

Ensure your SAP Cloud Connector is:
- Installed and connected to your SAP BTP sub-account
- Configured with a system mapping for your SAP ECC system (RFC protocol)

Refer to the [SAP Cloud Connector documentation](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/cloud-connector) for setup instructions.

### 1.2 BTP RFC Destination

In SAP BTP cockpit → **Connectivity** → **Destinations**, create an RFC destination:

| Property | Value |
|----------|-------|
| Name | Your RFC destination name (e.g., `ECC_RFC_ED3`) — this is referenced by the iFlow RFC adapters |
| Type | RFC |
| Location ID | Cloud Connector Location ID (e.g., `A1`) |
| Client | SAP ECC client number (e.g., `100`) |
| User | SAP ECC technical user with RFC authorization |
| Password | Technical user password |

> **Important:** The RFC destination name you choose here is the value you'll configure as `ECC RFC Destination` in the Sync All iFlow. The other flows have the destination name hardcoded in the adapter — if you use a different name, you'll need to update the RFC adapter configuration in each flow.

---

## Step 2: Create a Package in SAP BTP Integration Suite

1. Open your SAP BTP Integration Suite tenant.
2. Navigate to **Design** → **Integrations and APIs**.
3. Click **Create** → **Integration Package**.
4. Enter a name (e.g., `Amazon MCF ECC Integration`) and description.
5. Save the package — you'll import all iFlows into this package.

---

## Step 3: Import the iFlows

For each iFlow folder in `iFlows/`:

1. Open a terminal and navigate to the iFlow folder:
   ```bash
   cd iFlows/<Flow Folder Name>
   ```

2. Create a zip file **from inside the folder** (must include hidden files like `.project`):

   **macOS / Linux:**
   ```bash
   zip -r <FlowName>.zip .
   ```

   **Windows (PowerShell):**
   ```powershell
   Compress-Archive -Path .\* -DestinationPath <FlowName>.zip
   ```

3. In SAP BTP Integration Suite, open your package → **Artifacts** tab → **Add** → **Integration Flow** → **Upload** → select the zip file.

4. Repeat for each flow.

**Import order** (recommended):
1. Reusable Groovy Scripts for Amazon accelerators
2. Sync All Fulfillment Orders from SAP ECC to Amazon_MCF
3. Sync Selected Fulfillment Orders from SAP ECC to Amazon ECC
4. Create Fulfillment Order in Amazon MCF From ECC
5. Receive Delivery Event from Amazon MCF To ECC
6. Receive Delivery Notifications via SQS
7. Receive Package Delivery Fulfillment from Amazon MCF To ECC
8. Receive Package Delivery Cancel from Amazon_MCF To ECC

---

## Step 4: Configure Credentials in Secure Store

In SAP BTP Integration Suite → **Monitor** → **Security Material**, create the following credential artifacts:

| Artifact Name | Type | Contents |
|---------------|------|----------|
| `Amazon_SP_REST_Credentials` | User Credentials | **Username:** SP-API Client ID <br> **Password:** SP-API Client Secret |
| `Amazon_SP_REST_Refresh_Token` | Secure Parameter | **Value:** SP-API Refresh Token |

> **Note:** ECC credentials are configured on the BTP RFC Destination (Step 1), not in the CPI Secure Store.  

---

## Step 5: Configure iFlow Parameters

Open each iFlow in your package → **Configure** (top-right) → update the externalized parameters as described below.

### Common Configuration — SAP ECC Adapter

All flows connect to ECC via **RFC adapters**. The RFC adapter does not use iFlow-level credential, address, or proxy parameters — all connectivity is configured on the **BTP RFC Destination** (created in Step 1). The adapter only references the destination name.

| Field | Values | Description |
|-------|--------|-------------|
| ECC RFC Destination | `<Your RFC Destination>` | RFC destination name from SAP BTP Destinations (Step 1). This is the only ECC connectivity parameter consumed at runtime. |

---

### iFlow: Sync All Fulfillment Orders from SAP ECC to Amazon MCF

**Purpose:** Timer-based polling that discovers unprocessed deliveries in ECC and routes them to the Create Fulfillment Order flow.

| Section | Field | Values | Description |
|---------|-------|--------|-------------|
| Timer | Scheduler | _(Advanced Cron)_ | Configure polling frequency. Set start/end dates and cron expression in the Scheduler UI. |
| ECC Filter | Shipping Point | `1201` | ECC shipping point to filter deliveries |
| ECC Filter | Creation Date From | `YYYYMMDD` | Start of delivery creation date range |
| ECC Filter | Creation Date To | `YYYYMMDD` | End of delivery creation date range |
| ECC Filter | Sales Order Type | `OR1` | SAP order type to filter |
| ECC Filter | Sales Organization | `1000` | SAP sales organization |
| ECC Filter | Amazon Plant | `1200` | Plant code for MCF-eligible deliveries |
| ECC Filter | Partner Function | `WE` | Ship-to partner function (WE = goods recipient) |
| ECC Adapter | ECC RFC Destination | `<Your RFC Destination>` | RFC destination for BAPI calls to ECC |
| Processing | Long Text ID | `0001` | TDID used for idempotency marker text on delivery header |
| Processing | ProcessDirect Address | `/MCF/Fulfillment/CreateOrder` | Route to Create Fulfillment Order flow (do not change) |
| Error Handling | Custom Error Enabled | `FALSE` | Set `TRUE` to route errors to external handler |
| Error Handling | Enable Logging | `TRUE` | Enable message processing log entries |

---

### iFlow: Sync Selected Fulfillment Orders from SAP ECC to Amazon MCF

**Purpose:** Event-driven flow triggered by an ECC SOAP call when a delivery is created. Routes to the Create Fulfillment Order flow.

> **ECC-Side Configuration Required:** To trigger this flow, you must configure NACE output determination in SAP ECC. See [ECC Trigger Configuration Guide](ECC_Trigger_Configuration_Guide.md) for detailed instructions.

| Section | Field | Values | Description |
|---------|-------|--------|-------------|
| Sender (SOAP) | SOAP Address | `/RealTimeSync/SalesOrder` | Endpoint path where ECC posts delivery data |
| Sender (SOAP) | SOAP Authorization | `RoleBased` | CPI role-based authorization |
| Sender (SOAP) | SOAP User Role | `ESBMessaging.send` | Required CPI role for sender |
| Routing | Plant | `1200` | Only deliveries with this plant are routed to Amazon |
| Routing | Verify Order Type | `OR1` | Only this order type is processed |
| Routing | Partner Function | `AG` | Partner function to validate |
| Routing | Overall SDDocument Rejection Sts Expected | `A` | Expected rejection status (A = not rejected) |
| Routing | PD Create Address | `/MCF/Fulfillment/CreateOrder` | ProcessDirect route for order creation |
| Routing | PD Update Address | `/MCF/Fulfillment/UpdateOrder` | ProcessDirect route for updates |
| Processing | TextID | `0001` | TDID for idempotency text |
| Error Handling | Custom Error Handling | `false` | Set `true` to route errors externally |
| Error Handling | Enable Logging | `true` | Enable MPL logging |

---

### iFlow: Create Fulfillment Order in Amazon MCF From ECC

**Purpose:** Receives delivery data from Sync flows, fetches ship-to address from ECC, calls SP-API to create the fulfillment order in Amazon MCF, and marks the delivery as processed.

| Section | Field | Values | Description |
|---------|-------|--------|-------------|
| Receiver (ProcessDirect) | ProcessDirectEndpoint | `/MCF/Fulfillment/CreateOrder` | Inbound address from Sync flows (do not change) |
| SP-API Credentials | Selling Partner API Security Material | `Amazon_SP_REST_Credentials` | Secure store alias for SP-API Client ID + Secret |
| SP-API Credentials | Selling Partner API Security Parameter | `Amazon_SP_REST_Refresh_Token` | Secure store alias for SP-API Refresh Token |
| SP-API Credentials | Grant Type | `refresh_token` | OAuth grant type (do not change) |
| SP-API Credentials | Token Type | `bearer` | Token type (do not change) |
| SP-API Credentials | API Auth Header Key | `x-amz-access-token` | Header name for SP-API access token (do not change) |
| SP-API Create Order | SP_API_CreateOrder_Address | `https://sandbox.sellingpartnerapi-na.amazon.com/fulfillment/outbound/2026-07-04/orders` | SP-API endpoint. **Change to production URL when ready:** `https://sellingpartnerapi-na.amazon.com/fulfillment/outbound/2026-07-04/orders` |
| SP-API Create Order | SP_API_CreateOrder_Method | `POST` | HTTP method (do not change) |
| SP-API Create Order | SP_API_CreateOrder_ResponseCodes | `500,502,503` | HTTP codes that trigger automatic retry |
| SP-API Create Order | SP_API_CreateOrder_RetryInterval | `15` | Seconds between retries |
| SP-API Create Order | SP_API_CreateOrder_RetryIterations | `3` | Maximum retry attempts |
| SP-API Create Order | SP_API_CreateOrder_Timeout | `60000` | Request timeout in milliseconds |
| SP-API Token | SP_API_Token_Address | `https://api.amazon.com/auth/o2/token` | LWA token endpoint (do not change) |
| SP-API Token | SP_API_Token_RetryInterval | `60` | Token retry interval in seconds |
| SP-API Token | SP_API_Token_RetryIteration | `3` | Token retry attempts |
| Business Logic | Alias Type | `SELLER_ID` | Amazon alias type for order identification |
| Business Logic | Partner Function | `WE` | Ship-to partner function |
| Business Logic | Long Text ID | `0001` | TDID for idempotency marker |
| Business Logic | Language | `EN` | Language for text operations |
| Error Handling | Custom Error Enabled | `false` | Route errors to external handler |
| Error Handling | Enable Logging | `true` | Enable MPL logging |

---

### iFlow: Receive Delivery Event from Amazon MCF To ECC

**Purpose:** Event router — receives delivery notifications from the SQS listener flow (via ProcessDirect) and routes each to the appropriate handler flow based on event type (`Shipment` → Fulfillment, `Order` → Cancel).

| Section | Field | Values | Description |
|---------|-------|--------|-------------|
| Sender (ProcessDirect) | Address | `/Dev/DeliveryEvents` | Inbound from the SQS listener flow (must match its ProcessDirect receiver address) |
| Routing | addressInTransit | `/MCF/Fulfillment/PackageShipped` | ProcessDirect for shipment events |
| Routing | addressCancelled | `/MCF/Fulfillment/PackageCancelled` | ProcessDirect for cancel events |
| Error Handling | Enable Cust Error | `false` | Route errors externally |
| Error Handling | Enable Logging | `true` | Enable MPL logging |

---

### iFlow: Receive Package Delivery Fulfillment from Amazon MCF To ECC

**Purpose:** Processes Shipment events (`EventType` = `Shipment`, `FulfillmentOrderStatus` = `Complete`) — confirms picking, updates delivery dates, writes shipment tracking text, and posts Goods Issue in ECC. Partial (`COMPLETE_PARTIAL`) and in-progress (`PROCESSING`) shipments are rejected.

| Section | Field | Values | Description |
|---------|-------|--------|-------------|
| Receiver (ProcessDirect) | PD Sender Event Flow | `/MCF/Fulfillment/PackageShipped` | Inbound from Event Router (do not change) |
| Business Logic | Expected Alias Type | `SELLER_ID` | Amazon alias type to validate |
| Business Logic | Expected Line Item Alias Type | `EXTERNAL_ID` | Line item alias type |
| Business Logic | Item Unit | `ST` | Unit of measure for picking confirmation (ST = pieces) |
| Business Logic | Ship Point | `1201` | Shipping point for delivery updates |
| Business Logic | Shipping Instructions | `0001` | TDID for shipping instruction text |
| Business Logic | Shipping Notifications | `0001` | TDID for shipment tracking text |
| Business Logic | Tracking Information | `0001` | TDID for carrier tracking text |
| Business Logic | Language | `EN` | Language for text operations |
| Business Logic | Update Package Function | `true` | Enable package-level updates |
| Error Handling | Custom Error Handling | `false` | Route errors externally |
| Error Handling | Enable Logging | `true` | Enable MPL logging |

---

### iFlow: Receive Package Delivery Cancel from Amazon MCF To ECC

**Purpose:** Processes Order cancellation events (`EventType` = `Order`, `FulfillmentOrderStatus` = `Cancelled`) — validates the delivery hasn't shipped, then zeros out delivery quantities. Partial cancellations (`COMPLETE_PARTIAL`) are rejected.

| Section | Field | Values | Description |
|---------|-------|--------|-------------|
| Receiver (ProcessDirect) | PD Address Cancel Flow | `/MCF/Fulfillment/PackageCancelled` | Inbound from Event Router (do not change) |
| Business Logic | Alias Type ID | `SELLER_ID` | Amazon alias type to validate |
| Business Logic | Alias Type Item ID | `EXTERNAL_ID` | Line item alias type |
| Business Logic | Language | `EN` | Language for text operations |
| Error Handling | Custom Error Enabled | `false` | Route errors externally |
| Error Handling | Enable Logging | `false` | Enable MPL logging |

---

## Step 6: Event Delivery Setup (Inbound Flows)

The inbound flows (Fulfillment, Cancel) are triggered by Amazon MCF notifications delivered through **Amazon SQS**. Amazon publishes notifications to an SQS queue, and a dedicated listener iFlow polls that queue using the SAP BTP **Amazon Web Services (AWS) adapter**, forwarding each message to the event router.

### Architecture

```
Amazon MCF → SP-API Notifications → Amazon SQS queue
   → [Receive Delivery Notifications via SQS]  (AWS adapter, SQS sender — polls the queue)
   → (ProcessDirect /Dev/DeliveryEvents)
   → [Receive Delivery Event from Amazon MCF To ECC]  (event router)
   → (ProcessDirect) → Fulfillment / Cancel handler flows
```

### iFlow: Receive Delivery Notifications via SQS

**Purpose:** Polls the Amazon SQS queue using the SAP BTP AWS adapter (`AmazonWebServices`, message protocol `SQS`, `Sender`), extracts the notification `Payload` from the SQS message envelope via a Groovy script step (`extractPayload.groovy`), and forwards only that payload to the event router via ProcessDirect.

> **Note:** This flow is provided as a **basic reference implementation** — it reads messages from the SQS queue via the AWS adapter, extracts the notification payload, and forwards it to the event router. The AWS adapter itself is configurable, so polling and connection behavior can be tuned to your needs, and you can extend the flow (for example, to validate messages or handle processing errors) as your requirements dictate. See the SAP BTP Amazon Web Services adapter documentation for the adapter's available settings.

| Section | Field | Value | Description |
|---------|-------|-------|-------------|
| Sender (AWS adapter) | Account Number | _(your AWS account ID)_ | AWS account that owns the SQS queue |
| Sender (AWS adapter) | Queue Name | _(your SQS queue name)_ | The SQS queue Amazon publishes notifications to |
| Sender (AWS adapter) | Region | _(your region)_ | AWS region hosting the queue |
| Sender (AWS adapter) | Access Key / Secret Key | _(Security Material aliases)_ | Reference CPI **Security Material** entries; do not hardcode keys |
| Receiver (ProcessDirect) | Address | `/Dev/DeliveryEvents` | Hands off to the event router (must match the router's ProcessDirect sender address) |

> **Credentials:** The AWS adapter's Access Key / Secret Key fields reference CPI **Security Material** aliases (e.g. `sqsalias` / `sqssecret`) — create these in your tenant's Security Material store. Never place raw AWS keys in the iFlow. Other adapter settings (polling, authentication method, etc.) are left at their defaults; adjust them via the AWS adapter configuration as needed.

### Change the event router's sender to ProcessDirect

The **Receive Delivery Event from Amazon MCF To ECC** router receives from the SQS listener over ProcessDirect (not HTTPS). Its sender adapter is configured as:

| Property | Value |
|----------|-------|
| Adapter | ProcessDirect (Sender) |
| Address | `/Dev/DeliveryEvents` |

This address must match the ProcessDirect **Receiver** address in the SQS listener flow above, so the two flows connect.

### SP-API Event Subscription

Register for the **`FULFILLMENT_ORDER_STATUS`** notification (Fulfillment Outbound API 2020-07-01) in your SP-API Notifications subscription, with an **Amazon SQS** destination pointing at the queue the listener polls. This single notification delivers both event types the accelerator handles:

- `EventType` = `Shipment` — triggers the Fulfillment flow (package shipped)
- `EventType` = `Order` — triggers the Cancel flow (order cancelled)

Refer to the [SP-API Notifications documentation](https://developer-docs.amazon.com/sp-api/docs/notifications-api-v1-reference) for creating an SQS destination and subscription.

---

## Step 7: ECC-Side Trigger (Outbound — Optional)

The **Sync Selected** flow requires ECC to push delivery data to CPI when a delivery is saved. This is an optional enhancement — you can rely on the **Sync All** timer-based flow without any ECC configuration.

If you want real-time event-driven order creation:

→ See [ECC Trigger Configuration Guide](ECC_Trigger_Configuration_Guide.md) for detailed NACE output type setup and ABAP driver instructions.

---

## Step 8: Deploy and Verify

### Deployment order

Deploy flows in this order to avoid dependency issues:

1. **Reusable Groovy Scripts for Amazon accelerators** (script collection — deploy first)
2. **Receive Delivery Event from Amazon MCF To ECC** (event router)
3. **Receive Package Delivery Fulfillment from Amazon MCF To ECC**
4. **Receive Package Delivery Cancel from Amazon_MCF To ECC**
5. **Receive Delivery Notifications via SQS** (SQS listener — deploy after the router it forwards to)
6. **Create Fulfillment Order in Amazon MCF From ECC**
7. **Sync All Fulfillment Orders from SAP ECC to Amazon MCF** _(or)_
8. **Sync Selected Fulfillment Orders from SAP ECC to Amazon MCF**

### Verification steps

1. **Check deployment status:** Monitor → Integrations → verify all flows show "Started" status.
2. **Test outbound (SAP → Amazon):**
   - Create a sales order and delivery in ECC (VL01N).
   - If using Sync All: wait for the next scheduler poll.
   - If using Sync Selected: save the delivery to trigger the NACE output.
   - Check the CPI message monitor for a successful Amazon MCF response.
   - Verify `RFC_SAVE_TEXT` wrote the idempotency marker to the delivery header text.
3. **Test inbound (Amazon → SAP):**
   - Send a test event payload to the CPI HTTP endpoint (`/http/Dev/DeliveryEvents`).
   - Verify the event routes correctly and ECC delivery is updated.
4. **Check SP-API sandbox:** The Create flow defaults to the SP-API sandbox endpoint. Switch to production (`sellingpartnerapi-na.amazon.com`) when ready for live testing.

### Troubleshooting

| Symptom | Check |
|---------|-------|
| Flow fails with RFC connection error | Verify Cloud Connector mapping, RFC destination, and credential alias |
| SP-API returns 403 | Verify SP-API credentials and refresh token in Secure Store |
| Delivery not picked up by Sync All | Check shipping point, date range, and picking status (PKSTK = A) |
| Duplicate orders in Amazon | Verify `RFC_SAVE_TEXT` idempotency — check delivery header text (TDID `0001`) |
| Inbound event not processed | Check CPI HTTP endpoint URL, sender authorization role, and event JSON format |

---

## SP-API Version

This accelerator uses **Fulfillment Outbound API v2026-07-04**.

**Production endpoint:**
```
https://sellingpartnerapi-na.amazon.com/fulfillment/outbound/2026-07-04/orders
```

**Sandbox endpoint (default):**
```
https://sandbox.sellingpartnerapi-na.amazon.com/fulfillment/outbound/2026-07-04/orders
```

---

*Document Version: 1.0*  
*Last Updated: August 2026*  
*Maintained by: Amazon Supply Chain Services (ASCS) Solutions Architecture*
