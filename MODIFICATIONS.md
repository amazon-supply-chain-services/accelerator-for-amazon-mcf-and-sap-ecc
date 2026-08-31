# Modifications Notice

This repository contains integration flows for connecting SAP ECC to Amazon MCF
via SP-API. The work falls into two categories:

## Original Work (Amazon)

The ECC interface layer is **new, original work** by Amazon:

- RFC/BAPI connectivity to SAP ECC via Cloud Connector
- Sales order extraction from ECC (RFC function modules)
- Outbound delivery creation in ECC via BAPI calls
- ECC-specific field mappings and transformations
- Event routing and error handling for ECC integration patterns

## Derived Work (from SAP BwP Recipes)

Internal BTP processing routines are **derived from** the
[SAP API Business Hub Integration Recipes](https://github.com/SAP/apibusinesshub-integration-recipes/tree/master/Recipes/for/amazonmcfandbuywithprimeacceleratorsforsaps4hana),
originally published by SAP SE under the Apache License 2.0:

- Groovy script utilities for JSON/XML transformation
- SP-API authentication and token refresh patterns
- General iFlow structure and exception handling patterns

Modifications to derived components include adaptation for ECC-specific data
structures, removal of BwP/GraphQL dependencies, and alignment with the
Fulfillment Outbound API v2026-07-04.

Security credential information (API keys, client secrets, tokens, endpoint URLs)
has been blanked out from all artifacts to comply with Amazon security
requirements for public distribution.

## Test Harness (Amazon — original work)

The offline test harness under `test/` is original Amazon work. To execute the
production transformation scripts locally — without the SAP BTP Cloud Integration
(CPI) runtime, and without any SAP or Amazon connectivity — it includes:

- A minimal re-implementation of the SAP CPI script API **shape**: the class
  `com.sap.gateway.ip.core.customdev.util.Message` and the `messageLogFactory`
  binding. Only the class/API name and the method signatures the scripts call
  (`getBody`, `setBody`, `getProperty`, `setProperty`, `getProperties`,
  `setHeader`, `getMessageLog`) are reproduced, because the production scripts
  `import` that package name and must compile against it. The implementation is
  entirely original — no SAP-authored code is copied or included.
- A `groovy.util.XmlSlurper` compatibility shim that delegates to the standard
  `groovy.xml.XmlSlurper`, bridging the Groovy 2.4 package layout used by SAP CPI
  to the modern Groovy used by the tests. It does not alter the production scripts.

The harness depends only on Apache Groovy (Apache-2.0) and JUnit (EPL-2.0),
downloaded from Maven Central at build time. **No SAP libraries are bundled or
redistributed.**

Original source: https://github.com/SAP/apibusinesshub-integration-recipes
Original license: Apache-2.0
