# SAP ECC – Amazon MCF Integration Flows

## Technical Design Document

---

## 1. Solution Overview

This integration connects SAP ECC 6.0 (EHP 7.5) with Amazon Multi-Channel Fulfillment (MCF) via SAP BTP Cloud Integration (CPI). All ECC communication uses RFC adapters to the `ECC_RFC_ED3` destination.

**Key Design Principle:** The `orderId` sent to Amazon follows the pattern `<SalesOrder>.<DeliveryDoc>` (e.g., `0000022023.0080018885`). This allows downstream flows to extract both the sales order and delivery document numbers from a single identifier.

**Idempotency:** Delivery header text (`RFC_SAVE_TEXT` with TDID `0001`) is written after successful Amazon submission. Presence of this text indicates the delivery was already processed — preventing duplicate submissions.

**Base Package Reuse:** This accelerator is a self-contained set of iFlows that supports Amazon MCF integration via SP-API directly — it can be imported and configured as a single CPI package with no external base package required at runtime. It is *built* by reusing and upgrading the SAP-published base package ([SAP Accelerator for S/4HANA](https://github.com/SAP/apibusinesshub-integration-recipes/tree/9dd2486aa8827594635d064d591522a70f6ab540/Recipes/for/amazonmcfandbuywithprimeacceleratorsforsaps4hana), reference only). It inherits the base package's shared utilities — including the reusable Groovy scripts (e.g., logging, authentication, request builders) and common value mappings — and adapts them for the ECC RFC/BAPI integration pattern. Because these utilities are a shared library carried over from the base package, the collection includes scripts and helpers that support the broader accelerator family, and not every inherited script is invoked by every flow. The scripts wired into each flow's integration definition (`.iflw`) are the authoritative reference for what a given flow executes; inherited utilities that a flow does not reference are simply not part of that flow's runtime path.

**Inbound Connectivity (Amazon → SAP):** Amazon SP-API publishes `FULFILLMENT_ORDER_STATUS` notifications (2020-07-01) to an **Amazon SQS** queue. A dedicated listener iFlow, **Receive Delivery Notifications via SQS**, polls that queue using the SAP BTP **Amazon Web Services (AWS) adapter** (`AmazonWebServices`, message protocol `SQS`, `Sender`) and forwards each message over **ProcessDirect** (`/Dev/DeliveryEvents`) to the **Receive Delivery Event** router. The router inspects `EventType` and dispatches over ProcessDirect to the Fulfillment (`Shipment`) or Cancel (`Order`) handler. So the inbound chain is:

```
Amazon MCF → SP-API Notifications → Amazon SQS
   → Receive Delivery Notifications via SQS  (AWS adapter, SQS sender)
   → ProcessDirect /Dev/DeliveryEvents
   → Receive Delivery Event  (router, on EventType)
   → ProcessDirect → Fulfillment / Cancel handler
```

AWS credentials for the adapter are referenced via CPI Security Material aliases (never hardcoded).

---

## 2. Flow 1: Sync All Fulfillment Orders from SAP ECC to Amazon

**Purpose:** Scheduled polling flow that discovers unprocessed delivery documents in SAP ECC and routes each to the Create Fulfillment Order flow.

**Trigger:** Timer/Scheduler (periodic)

### Sequence Diagram

```
┌─────────┐          ┌─────────┐          ┌─────────┐
│  Timer  │          │   CPI   │          │ SAP ECC │
└────┬────┘          └────┬────┘          └────┬────┘
     │  trigger           │                     │
     │───────────────────>│                     │
     │                    │  BAPI_DELIVERY_GETLIST (by shipping point, date, PKSTK=A)
     │                    │────────────────────>│
     │                    │  List of VBELNs     │
     │                    │<────────────────────│
     │                    │                     │
     │                    │  ┌─── Loop per delivery ───┐
     │                    │  │                         │
     │                    │  │  RFC_READ_TEXT (check    │
     │                    │  │  if already processed)   │
     │                    │  │─────────────────────────>│
     │                    │  │  TDLINE response         │
     │                    │  │<─────────────────────────│
     │                    │  │                         │
     │                    │  │  [If text exists: SKIP] │
     │                    │  │  [If no text: fetch     │
     │                    │  │   full delivery detail] │
     │                    │  │                         │
     │                    │  │  BAPI_DELIVERY_GETLIST   │
     │                    │  │  (specific delivery)     │
     │                    │  │─────────────────────────>│
     │                    │  │  Full delivery data      │
     │                    │  │<─────────────────────────│
     │                    │  │                         │
     │                    │  │  Route to Create Flow    │
     │                    │  │  (ProcessDirect)         │
     │                    │  └─────────────────────────┘
     │                    │                     │
```

### RFC/BAPI Calls

| Step | Function Module | Purpose |
|------|----------------|---------|
| 1 | `BAPI_DELIVERY_GETLIST` | Fetch deliveries by shipping point + date range + PKSTK=A (not picked) |
| 2 | `RFC_READ_TEXT` | Idempotency check — if header text exists, skip |
| 3 | `BAPI_DELIVERY_GETLIST` | Fetch full delivery detail for specific VBELN |

### Key Logic
- Filters by configurable `shippingPoint` and `creationDateFrom`/`creationDateTo`
- Only picks deliveries with picking status `PKSTK = A` (not yet picked)
- Idempotency: checks delivery header text (TDID from config) — if text exists, delivery was already sent to Amazon
- Routes unprocessed deliveries to the Create Fulfillment Order flow via ProcessDirect

---

## 3. Flow 2: Sync Selected Fulfillment Orders from SAP ECC to Amazon

**Purpose:** Event-driven flow triggered by an ECC SOAP call for a specific delivery. Validates the delivery item plant against the configured `amazonPlant` and, on a match, routes the delivery to the Create Fulfillment Order flow.

**Trigger:** SOAP sender (see the [ECC Trigger Configuration Guide](ECC_Trigger_Configuration_Guide.md))

### Sequence Diagram

```
┌──────────┐          ┌─────────┐          ┌──────────────┐
│ SAP ECC  │          │   CPI   │          │ Amazon Flows │
│          │          │         │          │              │
└────┬─────┘          └────┬────┘          └──────┬───────┘
     │  SOAP call          │                      │
     │────────────────────>│                      │
     │                     │  Validate plant      │
     │                     │  (verifyLongTextID)  │
     │                     │                      │
     │                     │  [Plant matches?]    │
     │                     │  Yes → Route to:     │
     │                     │    Create flow       │
     │                     │─────────────────────>│
     │                     │                      │
     │                     │  [Plant mismatch?]   │
     │                     │  Skip (log warning)  │
     │                     │                      │
```

### Key Logic
- Receives delivery data via SOAP from SAP ECC
- `verifyLongTextID`: validates that delivery items have a plant matching the configured `amazonPlant`
- If plant matches → routes via ProcessDirect to the Create Fulfillment Order flow
- If no match → sets `createOrder = 'no'` and skips (logs a precheck failure via `setCustomHeader`)

---

## 4. Flow 3: Create Fulfillment Order in Amazon

**Purpose:** Takes a delivery document from ECC, fetches ship-to address from the sales order, builds Amazon SP-API createFulfillmentOrder request, calls Amazon, then marks the delivery as processed in ECC.

**Trigger:** ProcessDirect from Sync flows (receives BAPI_DELIVERY_GETLIST response as body)

### Sequence Diagram

```
┌─────────┐          ┌─────────┐          ┌─────────┐          ┌────────┐
│Sync Flow│          │   CPI   │          │   ECC   │          │ Amazon │
└────┬────┘          └────┬────┘          └────┬────┘          └───┬────┘
     │  ProcessDirect     │                     │                   │
     │  (delivery data)   │                     │                   │
     │───────────────────>│                     │                   │
     │                    │                     │                   │
     │                    │  extractDeliveryData                    │
     │                    │  (parse VBELN, items,                   │
     │                    │   VGBEL=sales order)                    │
     │                    │                     │                   │
     │                    │  BAPISDORDER_GETDETAILEDLIST            │
     │                    │  (fetch ship-to address)                │
     │                    │────────────────────>│                   │
     │                    │  Address data        │                   │
     │                    │<────────────────────│                   │
     │                    │                     │                   │
     │                    │  buildCreateOrderRequest                │
     │                    │  (build SP-API JSON)                    │
     │                    │                     │                   │
     │                    │  OAuth token request │                   │
     │                    │─────────────────────────────────────────>│
     │                    │  Access token        │                   │
     │                    │<─────────────────────────────────────────│
     │                    │                     │                   │
     │                    │  POST createFulfillmentOrder             │
     │                    │─────────────────────────────────────────>│
     │                    │  201 Created         │                   │
     │                    │<─────────────────────────────────────────│
     │                    │                     │                   │
     │                    │  RFC_SAVE_TEXT       │                   │
     │                    │  (mark as processed) │                   │
     │                    │────────────────────>│                   │
     │                    │  OK                  │                   │
     │                    │<────────────────────│                   │
     │                    │                     │                   │
```

### RFC/BAPI Calls

| Step | Function Module | Purpose |
|------|----------------|---------|
| 1 | `BAPISDORDER_GETDETAILEDLIST` | Fetch ECC sales order ship-to address (partner + address) |
| 2 | `RFC_SAVE_TEXT` | Write delivery header text with `OrderId: <SO>.<DelDoc>` (idempotency marker) |

### Amazon API Call
- **Endpoint:** SP-API `POST /fulfillment/outbound/2026-07-04/orders` (host is environment-specific — e.g., `sellingpartnerapi-na.amazon.com`, or the `sandbox.sellingpartnerapi-na.amazon.com` sandbox host; configured via the `SP_API_CreateOrder_Address` parameter)
- **orderId format:** `<SalesOrder>.<DeliveryDoc>` (e.g., `0000022023.0080018885`)
- **displayableOrderId:** Uses the bare sales order number (e.g., `0000022023`)
- **lineItemId:** Uses `VGPOS` (sales order item number from delivery)
- **amazonSku:** Uses `MATNR` (material number)
- **quantity unit:** Hardcoded to `EACHES` in the SP-API line items — the ECC `itemUnit` parameter is **not** applied in this flow (it is used only by the Fulfillment flow for picking)

### Key Logic
- **Data sourcing:** Line items (SKU, quantity, `lineItemId`) come from the **delivery document** (`BAPI_DELIVERY_GETLIST` response). The **sales order** (`BAPISDORDER_GETDETAILEDLIST`) is fetched **only** to obtain the ship-to address — it is not the source of line items.
- Phase 1 (`extractDeliveryData`): Parses ECC delivery GETLIST response, extracts items (POSNR, MATNR, LFIMG, VGPOS), finds WE partner ADRNR, builds address fetch request
- Phase 2 (`buildCreateOrderRequest`): Matches address by ADRNR, builds SP-API JSON with delivery address + line items
- After successful Amazon call: writes header text via `RFC_SAVE_TEXT` to mark delivery as processed in ECC

---

## 5. Flow 4: Receive Delivery Notifications via SQS

**Purpose:** Inbound entry point for all Amazon MCF notifications. Polls the Amazon SQS queue using the SAP BTP Amazon Web Services (AWS) adapter, extracts the notification `Payload` from the SQS message envelope, and forwards only that payload to the Event Router over ProcessDirect. A Groovy script step performs the extraction; no other transformation is applied.

> **Note:** This flow is a **basic reference implementation** that demonstrates SQS-based inbound delivery. Its scope is intentionally limited to reading messages from the queue and handing them to the Event Router; it does not implement message validation, error/failure handling, or delivery-guarantee logic beyond what the AWS adapter provides. Extend it as your requirements dictate — for example, validating or filtering incoming notifications, defining how failed or unprocessable messages are handled, and hardening authentication.

**Trigger:** SQS polling — AWS adapter (`AmazonWebServices`, message protocol `SQS`, `Sender`), polling on a configurable interval.

### Sequence Diagram

```
┌────────┐          ┌─────────────────────┐          ┌──────────────┐
│ Amazon │          │  Receive Delivery   │          │ Event Router │
│  SQS   │          │  Notifications/SQS  │          │              │
└───┬────┘          └──────────┬──────────┘          └──────┬───────┘
    │  poll (AWS adapter)      │                            │
    │<─────────────────────────│                            │
    │  message(s)              │                            │
    │─────────────────────────>│                            │
    │                          │  extractPayload            │
    │                          │  (unwrap SQS envelope,     │
    │                          │   keep Payload only)       │
    │                          │                            │
    │                          │  ProcessDirect             │
    │                          │  /Dev/DeliveryEvents        │
    │                          │───────────────────────────>│
    │                          │                            │
```

### Adapter / Connectivity

| Element | Value | Purpose |
|---------|-------|---------|
| Sender adapter | AWS adapter (`AmazonWebServices`, `SQS`, `Sender`) | Polls the SQS queue |
| Queue | _(externalized — your SQS queue)_ | Queue Amazon publishes notifications to |
| Authentication | Access Key / Secret Key via Security Material aliases | Credentials never hardcoded in the iFlow |
| Receiver adapter | ProcessDirect | Hands off to the Event Router |
| ProcessDirect address | `/Dev/DeliveryEvents` | Must match the Event Router's ProcessDirect sender address |

### Key Logic
- A Groovy script step (`extractPayload.groovy`) unwraps the SQS notification envelope and forwards only the inner `Payload` object (`{ "FulfillmentOrderStatusNotification": { ... } }`) — the shape the Event Router expects. Envelope fields such as `NotificationType` and `NotificationId` are surfaced as message properties/headers for traceability; if a message is already unwrapped, it is passed through unchanged.
- Poll interval and max-messages-per-poll are configurable on the AWS adapter.
- AWS account number, queue, and region are environment-specific; credentials are referenced as CPI Security Material aliases.

---

## 6. Flow 5: Receive Delivery Event (Event Router)

**Purpose:** Content-based router for inbound notifications. Parses the Fulfillment Outbound API 2020-07-01 `FulfillmentOrderStatusNotification`, maps the `EventType` to a routing key, and dispatches over ProcessDirect to the correct handler flow.

**Trigger:** ProcessDirect from the SQS listener flow (`/Dev/DeliveryEvents`).

### Sequence Diagram

```
┌──────────────┐          ┌─────────────────────┐          ┌──────────────────┐
│ SQS Listener │          │  Receive Delivery   │          │ Fulfillment /    │
│              │          │  Event (Router)     │          │ Cancel handler   │
└──────┬───────┘          └──────────┬──────────┘          └────────┬─────────┘
       │  ProcessDirect              │                              │
       │  /Dev/DeliveryEvents         │                              │
       │────────────────────────────>│                              │
       │                             │  extractDeliveryDetails      │
       │                             │  (parse EventType,           │
       │                             │   map to routing key)        │
       │                             │                              │
       │                             │  ProcessDirect (by eventType)│
       │                             │─────────────────────────────>│
       │                             │                              │
```

### Routing Logic

The script `deliveryEventFunctions.groovy` (`extractDeliveryDetails`) reads `FulfillmentOrderStatusNotification.EventType` and `SellerFulfillmentOrderId`, then maps the legacy `EventType` to the router's existing routing keys and sets the `eventType` message property:

| Notification `EventType` | Mapped routing key (`eventType`) | Routed to |
|--------------------------|----------------------------------|-----------|
| `Shipment` | `SHIPMENT_STATUS_CHANGED` | Fulfillment flow (`/MCF/Fulfillment/PackageShipped`) |
| `Order` | `ORDER_STATUS_CHANGED` | Cancel flow (`/MCF/Fulfillment/PackageCancelled`) |
| _(anything else)_ | passed through unmapped | Router default / error path |

The content-based router evaluates `${property.eventType}` against these keys. `SellerFulfillmentOrderId` (`<SalesOrder>.<DeliveryDoc>`) is captured to the `orderId` property and `SAP_ApplicationID` header for downstream correlation and MPL traceability.

### Key Logic
- The router branch conditions in the `.iflw` are unchanged (`SHIPMENT_STATUS_CHANGED`, `ORDER_STATUS_CHANGED`); the script maps the 2020-07-01 `EventType` onto them so the existing routing table is preserved.
- The full notification payload is passed forward unchanged to the handler.
- No RFC/BAPI calls — this flow is CPI-internal routing only.

---

## 7. Flow 6: Receive Package Delivery Fulfillment from Amazon

**Purpose:** When Amazon ships a package (Shipment event, `FulfillmentOrderStatus` = `Complete`), this flow receives the notification, validates the ECC delivery document, confirms picking, updates dates, writes shipment text, and posts Goods Issue. Partial (`COMPLETE_PARTIAL`) and in-progress (`PROCESSING`) shipments are rejected.

**Trigger:** ProcessDirect from Event Router (`EventType` = `Shipment`)

### Sequence Diagram

```
┌────────┐          ┌─────────┐          ┌─────────┐
│ Amazon │          │   CPI   │          │ SAP ECC │
│ Event  │          │         │          │         │
└───┬────┘          └────┬────┘          └────┬────┘
    │  Shipment event     │                    │
    │  (Complete)         │                    │
    │────────────────────>│                    │
    │                     │                    │
    │                     │  processData       │
    │                     │  (split orderId,   │
    │                     │   build GETLIST)   │
    │                     │                    │
    │                     │  BAPI_DELIVERY_GETLIST
    │                     │───────────────────>│
    │                     │  Delivery data     │
    │                     │<───────────────────│
    │                     │                    │
    │                     │  extractDeliveryNumber
    │                     │  (validate ALL line│
    │                     │   qtys match)      │
    │                     │                    │
    │                     │  WS_DELIVERY_UPDATE│
    │                     │  (confirm picking) │
    │                     │───────────────────>│
    │                     │  OK                │
    │                     │<───────────────────│
    │                     │                    │
    │                     │  BAPI_OUTB_DELIVERY_CHANGE
    │                     │  (update dates)    │
    │                     │───────────────────>│
    │                     │  OK                │
    │                     │<───────────────────│
    │                     │                    │
    │                     │  RFC_SAVE_TEXT     │
    │                     │  (shipment text)   │
    │                     │───────────────────>│
    │                     │  OK                │
    │                     │<───────────────────│
    │                     │                    │
    │                     │  WS_DELIVERY_UPDATE│
    │                     │  (Post Goods Issue)│
    │                     │───────────────────>│
    │                     │  OK                │
    │                     │<───────────────────│
    │                     │                    │
```

### RFC/BAPI Calls

| Step | Function Module | Purpose |
|------|----------------|---------|
| 1 | `BAPI_DELIVERY_GETLIST` | Fetch delivery doc to validate line quantities |
| 2 | `WS_DELIVERY_UPDATE` | Confirm picking (UPDATE_PICKING=X, PIKMG=qty, TAQUI=X) |
| 3 | `BAPI_OUTB_DELIVERY_CHANGE` | Update planned GI date and picking date (HEADER_DEADLINES) |
| 4 | `RFC_SAVE_TEXT` | Write shipment tracking info to delivery header text (ShipmentId, Status, Carrier, Tracking, Fulfillment Center) |
| 5 | `WS_DELIVERY_UPDATE` | Post Goods Issue (WABUC=X, WADAT_IST=today) |

### Key Logic
- Splits `orderId` on `.` to get delivery doc number
- **Partial fulfillment is not supported.** The flow validates that every shipment line matches the corresponding ECC delivery line on both line number and quantity. If any line is missing or a quantity differs, the flow **throws an exception and stops** — picking is not confirmed, no shipment text is written, and Goods Issue is **not** posted. The delivery is left untouched so it can be reprocessed once corrected.
- Confirms picking with `TAQUI=X` (transfer order not required)
- Updates delivery dates (Planned GI `WSHDRWADAT` and Picking `WSHDRKODAT`) to today (UTC)
- Writes shipment metadata (ShipmentId, Status, Carrier, Tracking, Fulfillment Center) to delivery header text
- Text TDID is driven by the `shippingNotifications` parameter (see §10); if unset, the script falls back to `0001`
- Posts Goods Issue to complete the delivery

---

## 8. Flow 7: Receive Package Delivery Cancel from Amazon

**Purpose:** When Amazon cancels a fulfillment order (Order event, `FulfillmentOrderStatus` = `Cancelled`), this flow validates the ECC delivery hasn't been shipped yet, then zeros out the delivery quantity. Partial cancellations (`COMPLETE_PARTIAL`) are rejected.

**Trigger:** ProcessDirect from Event Router (`EventType` = `Order`)

### Sequence Diagram

```
┌────────┐          ┌─────────┐          ┌─────────┐
│ Amazon │          │   CPI   │          │ SAP ECC │
│ Event  │          │         │          │         │
└───┬────┘          └────┬────┘          └────┬────┘
    │  Order event        │                    │
    │  (Cancelled)        │                    │
    │────────────────────>│                    │
    │                     │                    │
    │                     │  processData       │
    │                     │  (split orderId →  │
    │                     │   SO + delivery)   │
    │                     │                    │
    │                     │  BAPI_DELIVERY_GETLIST
    │                     │  (fetch status)    │
    │                     │───────────────────>│
    │                     │  Status response   │
    │                     │<───────────────────│
    │                     │                    │
    │                     │  parseDeliveryStatusAndValidate
    │                     │  Check GBSTK = 'A' │
    │                     │  (not processed)   │
    │                     │                    │
    │                     │  [GBSTK != 'A'?    │
    │                     │   THROW EXCEPTION] │
    │                     │                    │
    │                     │  WS_DELIVERY_UPDATE│
    │                     │  (LIPS_DEL=X,      │
    │                     │   zero all lines)  │
    │                     │───────────────────>│
    │                     │  OK                │
    │                     │<───────────────────│
    │                     │                    │
```

### RFC/BAPI Calls

| Step | Function Module | Purpose |
|------|----------------|---------|
| 1 | `BAPI_DELIVERY_GETLIST` | Fetch delivery header status (GBSTK) |
| 2 | `WS_DELIVERY_UPDATE` | Zero delivery qty (LIPS_DEL=X on all lines) |

### Key Logic
- Splits `orderId` on `.` to get sales order + delivery doc
- Calls `BAPI_DELIVERY_GETLIST` and checks `GBSTK` in `ET_DELIVERY_HEADER_STS`:
  - `'A'` = ECC delivery not yet processed → proceed with cancellation
  - Anything else = already shipped/processed in ECC → throw exception
- Validates no partial cancel: cancel request line count must equal ECC delivery line count
- Zeros out all ECC delivery lines with `LIPS_DEL=X`

---

## 9. RFC Destination & Adapter Configuration

| Property | Value |
|----------|-------|
| RFC Destination | `ECC_RFC_ED3` |
| Transaction Commit | Enabled (on adapter) |
| New Connection | Disabled (reuse) |
| Protocol | Synchronous RFC |

---

## 10. Externalized Parameters Summary

| Parameter | Used In | Purpose |
|-----------|---------|---------|
| `shippingPoint` | Sync All | Filter deliveries by shipping point (e.g., `1201`) |
| `creationDateFrom` / `creationDateTo` | Sync All | Date range filter |
| `amazonPlant` | Sync Selected | Plant filter for eligible items (e.g., `1200`) |
| `longTextId` | Create, Sync All | TDID for idempotency text (e.g., `0001`) |
| `shippingNotifications` | Fulfillment | TDID for shipment tracking text (e.g., `TX04`) |
| `itemUnit` | Fulfillment | Unit of measure for picking (e.g., `ST`) |
| `rejectionReason` | Cancel (legacy) | SAP rejection reason code |
| `PD Address Cancel Flow` | Event Router | ProcessDirect address for cancel |

---

## 11. Error Handling Pattern

All flows share a common exception handling pattern:
1. Exception subprocess catches errors
2. Routes to "Common Exception Handling" local process
3. If `custErrorEnabled = 'true'` → routes to external Custom Error Handling iFlow via ProcessDirect
4. Otherwise → logs exception via reusable `logException.groovy` script

---

## 12. Data Flow Summary

```
SAP ECC Delivery Doc
        │
        ▼
┌─────────────────────────┐
│ Sync All / Sync Selected│  ← Discovers deliveries
│ (BAPI_DELIVERY_GETLIST) │
└───────────┬─────────────┘
            │ ProcessDirect
            ▼
┌─────────────────────────┐
│ Create Fulfillment Order│  ← Sends to Amazon
│ (BAPISDORDER_GETDETAILED│     orderId = SO.DelDoc
│  + SP-API POST)         │
│ (RFC_SAVE_TEXT marker)  │
└─────────────────────────┘
            │
            │ Amazon processes...
            │
            ▼
Amazon SQS → Receive Delivery Notifications via SQS (AWS adapter)
            │ ProcessDirect /Dev/DeliveryEvents
            ▼
      Receive Delivery Event (router, on EventType)
            │ ProcessDirect
            ▼
┌─────────────────────────┐     ┌─────────────────────────┐
│ Receive Fulfillment     │     │ Receive Cancel          │
│ (BAPI_DELIVERY_GETLIST  │     │ (BAPI_DELIVERY_GETLIST  │
│  WS_DELIVERY_UPDATE x2  │     │  WS_DELIVERY_UPDATE     │
│  BAPI_OUTB_DELIVERY_CHG │     │  LIPS_DEL=X)            │
│  RFC_SAVE_TEXT           │     │                         │
│  → Pick + GI)           │     │ → Zero qty              │
└─────────────────────────┘     └─────────────────────────┘
```

---

*Document Version: 1.0*
*Last Updated: August 2026*
*Platform: SAP BTP Cloud Integration (CPI) → SAP ECC 6.0 EHP 7.5 via RFC*
