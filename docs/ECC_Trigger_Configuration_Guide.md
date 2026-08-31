# ECC Event Trigger for Amazon MCF Delivery Push

August 2026

# 1. Overview

This guide describes how to automatically push a delivery from SAP ECC to the Amazon MCF Cloud Integration (CPI) iFlow endpoint when the delivery is saved (unprocessed state, before Post Goods Issue). The push carries the DeliveryReplication payload consumed by the CPI "Sync Selected Fulfillment Orders" iFlow.

On classic SAP ECC there is no config-only way to drive an outbound SOAP call from an event like "delivery saved" — NACE output determination always calls an ABAP routine, and no transmission medium in NACE maps directly to a consumer proxy invocation. Both paths in this guide therefore include a small ABAP driver. The paths differ in whether the SOAP call is delegated to a SAP consumer proxy (Path A, ~10 lines of driver code) or performed directly with CL_HTTP_CLIENT (Path B, ~100 lines).

# 2. Why not IDoc DELVRY?

IDoc DELVRY is a natural candidate for a low-code outbound trigger, and the CPI side has full support for IDoc receive endpoints. The blocker is entirely on the ECC trigger side: standard DELVRY output fires on Post Goods Issue (VBUK-WBSTK = C), not on delivery save. The accelerator design requires the delivery to be pushed in its unprocessed state, before PGI. Making DELVRY fire pre-PGI on ECC requires either a custom output-type variant or a user-exit, which reintroduces custom code and eliminates the reason for using IDoc in the first place. IDoc is therefore not used here.

# 3. Prerequisites

## 3.1 CPI endpoint and credentials

The CPI iFlow endpoint accepts either OAuth2 client-credentials or HTTP Basic authentication using the same service-key clientid and secret. Both paths in this guide use HTTP Basic.

Endpoint URL (provided by the CPI operator):

https://\<cpi-runtime-host\>/cxf/RealTimeSync/SalesOrder

Credentials — the clientid and clientsecret of a Process Integration Runtime service key:

- clientid — used as HTTP Basic username

- clientsecret — used as HTTP Basic password

## 3.2 STRUST certificates

The CPI runtime host TLS chain and its authentication host chain must be imported into the SSL Client PSE used for the outbound call.

- SSL Client (Standard) — used for SM59 destinations and for the consumer-proxy path.

- SSL Client (Anonymous) — used when CL_HTTP_CLIENT is invoked with ssl_id = 'ANONYM'.

Import via tx STRUST → double-click the target PSE → right-click certificate list → Import from file → Add to Certificate List.

## 3.3 Master-data and customizing prerequisites

Storage-location determination must produce a non-blank LIPS-LGORT at delivery creation. If LGORT is blank at save time, SAP raises VL245 and the delivery cannot be picked or PGI'd later. Three settings drive this:

- MARC-LGPRO (Production storage location) on each material at the shipping plant — MM02 → MRP 2 view.

- MLAN-TAXM1 (tax classification) for the sold-from country on each material — MM02 → Sales: sales org 1 → Tax data. Blank TAXM1 triggers VL245 which strips LGORT.

- TVKOL row for the shipping-point / plant / storage-condition combination pointing to the storage location that actually holds stock — SM30 view V_TVKOL.

# 4. Path A — Consumer proxy driver

This path uses a SAP consumer proxy generated from the CPI-provided WSDL. Authentication, TLS, message serialization and headers are handled by the SAP SOAP framework. The NACE driver is a short wrapper (~10 lines) that instantiates the proxy and calls the outbound method. This is the more elegant path when the SOAP runtime is fully operational.

## 4.1 Generate consumer proxy from WSDL

- The CPI operator supplies a WSDL for the DeliveryReplication payload (schema-less, xsd:any, namespace http://sap.com/xi/SD-SLS).

- tx SPROXY → Create → Service Consumer → External WSDL / Schema → Local File.

- Package: your Y or Z package. Prefix: Y or Z. Example generated class name: YCO_DELIVERY_REPLICATION_OUT.

- Save and Activate.

## 4.2 Configure logical port in SOAMANAGER

- tx SOAMANAGER → Web Service Configuration → search for the generated proxy class.

- Create → Manual Configuration.

- Logical Port Name: e.g. ZLP_DELIVERY_REPL. Mark as Default.

- Consumer Security tab: HTTP → User ID/Password. User = clientid, Password = client secret.

- HTTPSettings tab: URL Access Path = /cxf/RealTimeSync/SalesOrder; Computer Name = CPI runtime host; Port = 443; URL Protocol = HTTPS; Transport Binding = SOAP 1.1.

- Messaging tab: RM Protocol = No Reliable Messaging (leave blank if greyed); Message ID Protocol = WS-A Message ID.

- Save. Use Ping Web Service to validate connectivity.

## 4.3 NACE driver report

The NACE processing routine dialog exposes Program + FORM routine. The driver report holds a short FORM ENTRY that instantiates the proxy and calls the outbound method:

REPORT z_bwp_delivery_output_proxy.\
TABLES: nast.\
\
FORM entry USING return_code us_screen.\
DATA lo_proxy TYPE REF TO yco_delivery_replication_out.\
DATA ls_input TYPE ydelivery_replication_request.\
DATA ls_output TYPE ydelivery_replication_response.\
\
TRY.\
" Build the xsd:any body from LIKP/LIPS/VBPA for NAST-OBJKY\
PERFORM build_payload USING nast-objky CHANGING ls_input.\
\
CREATE OBJECT lo_proxy\
EXPORTING logical_port_name = 'ZLP_DELIVERY_REPL'.\
lo_proxy-\>delivery_replication_out(\
EXPORTING input = ls_input\
IMPORTING output = ls_output ).\
\
return_code = 0.\
CATCH cx_ai_system_fault cx_root.\
return_code = 5.\
ENDTRY.\
ENDFORM.

The build_payload subroutine populates the xsd:any input structure from LIKP header, LIPS items, and VBPA partners — same shape as the payload in Path B.

## 4.4 NACE output type

- tx NACE → application V2 (Shipping) → Output types → New Entries: ZBWP.

- Access Sequence: Delivery Type.

- Default values: Transmission Medium 8 (Special function), Dispatch time 4 (Send immediately when saved), Partner function SP.

- Processing routines: Medium 8, Program = Z_BWP_DELIVERY_OUTPUT_PROXY, FORM routine = ENTRY.

- Partner functions: assign the partner function used above for medium 8.

## 4.5 Add to procedure V10000 and create condition record

- NACE → Procedures → V10000 → Control data → add step for CnTy = ZBWP with Requirement blank.

- tx VV21 → Output type ZBWP → key combination "Delivery Type" → Delivery Type = LF, Medium = 8, Time = 4, Language = EN, Partner function SP. Save.

## 4.6 Known limitation encountered on the reference IDES

On the reference IDES tenant used to develop this accelerator, the SOAP runtime raised CX_SY_REF_IS_INITIAL at proxy execute time, although SOAMANAGER Ping Web Service was successful. The root cause was a non-operational WSS / kernel crypto library (report WSS_SETUP dumped on SSF_KRN_VERSION). This is a Basis / kernel-level issue on that host, not a defect in the proxy or logical port configuration. On an ECC system with a working WSS framework this path functions as documented. If the same symptom appears elsewhere, run WSS_SETUP and check SRT_ADMIN, or fall back to Path B.

# 5. Path B — Direct HTTP driver (verified working)

This path replaces the consumer proxy with a small ABAP driver that builds the SOAP envelope directly and posts it via CL_HTTP_CLIENT. It bypasses SPROXY and the SOAP framework entirely. It has been verified end-to-end on the reference IDES.

## 5.1 Function module Y_BWP_DELIVERY_OUTPUT_SEND

Create in SE37 (function group YBWP or your equivalent). Signature:

Importing:

- EXT_NAST — type NAST

- EXT_XNAST — type ARC_PARAMS (optional)

- EXT_SCREEN — type C (optional)

- EXT_XDEVICE — type ITCPO-TDDEST (optional)

- EXT_DIALOG — type C (optional)

Exporting:

- RETURN_CODE — type SY-SUBRC

- EXT_XPARAMS — type ITCPP

Source outline (illustrative — adapt to your system's naming and standards):

FUNCTION y_bwp_delivery_output_send.\
" Read delivery header, items, partners\
SELECT SINGLE \* FROM likp INTO ls_likp WHERE vbeln = ext_nast-objky.\
SELECT \* FROM lips INTO TABLE lt_lips WHERE vbeln = ls_likp-vbeln.\
SELECT \* FROM vbpa INTO TABLE lt_vbpa\
WHERE vbeln = ls_likp-vbeln AND posnr = '000000'.\
\
" Build \<DeliveryReplication\> SOAP envelope (BAPI-flat table shape)\
lv_body = \|\<?xml version="1.0" encoding="UTF-8"?\>\| &&\
\|\<soap:Envelope xmlns:soap=".../envelope/"\>\<soap:Body\>\| &&\
\|\<DeliveryReplication xmlns="http://sap.com/xi/SD-SLS"\>\| &&\
... ET_DELIVERY_HEADER / ITEM / PARTNER ... &&\
\|\</DeliveryReplication\>\</soap:Body\>\</soap:Envelope\>\|.\
\
" Basic-auth header: base64(clientid:clientsecret)\
CALL FUNCTION 'SSFC_BASE64_ENCODE' ...\
\
" Direct URL — bypasses SM59 to avoid client-cert conflict at CPI\
cl_http_client=\>create_by_url(\
EXPORTING url = '\<CPI endpoint\>'\
ssl_id = 'ANONYM'\
IMPORTING client = lo_http ).\
\
lo_http-\>request-\>set_header_field( name = 'Authorization'\
value = \|Basic { lv_upb64 }\| ).\
lo_http-\>request-\>set_header_field( name = 'Content-Type'\
value = 'text/xml; charset=utf-8' ).\
lo_http-\>request-\>set_method( if_http_request=\>co_request_method_post ).\
lo_http-\>request-\>set_cdata( lv_body ).\
lo_http-\>send( ). lo_http-\>receive( ).\
lo_http-\>response-\>get_status( IMPORTING code = lv_status ).\
\
IF lv_status BETWEEN 200 AND 299. return_code = 0.\
ELSE. return_code = 5. ENDIF.\
ENDFUNCTION.

Notes:

- CL_HTTP_CLIENT=\>create_by_url with ssl_id = 'ANONYM' uses the SSL Client (Anonymous) PSE and does not present ECC's own client certificate. Using an SM59 destination in this scenario causes CPI to interpret ECC's client cert as a service-key certificate and returns InvalidClientException. Direct URL avoids that.

- The Authorization header is set explicitly regardless of any destination configuration.

## 5.2 NACE wrapper report Z_BWP_DELIVERY_OUTPUT

The NACE processing routine dialog exposes Program + FORM routine (no direct FM call). A minimal wrapper report exposes a FORM ENTRY that calls the FM:

REPORT z_bwp_delivery_output.\
TABLES: nast.\
\
FORM entry USING return_code us_screen.\
DATA lv_xnast TYPE arc_params.\
DATA lv_xparams TYPE itcpp.\
\
CALL FUNCTION 'Y_BWP_DELIVERY_OUTPUT_SEND'\
EXPORTING ext_nast = nast\
ext_xnast = lv_xnast\
ext_screen = us_screen\
IMPORTING return_code = return_code\
ext_xparams = lv_xparams.\
ENDFORM.

## 5.3 NACE output type

- tx NACE → application V2 → Output types → New Entries: ZBWP.

- Access Sequence: Delivery Type.

- Default values: Transmission Medium 8 (Special function), Dispatch time 4, Partner function SP.

- Processing routines: Medium 8, Program = Z_BWP_DELIVERY_OUTPUT, FORM routine = ENTRY.

- Partner functions: assign the partner function used above for medium 8.

## 5.4 Add to procedure V10000 and create condition record

- NACE → Procedures → V10000 → Control data → add step for CnTy = ZBWP with Requirement blank. Requirement 408 is incompatible with this output on the reference system and must be left blank.

- tx VV21 → Output type ZBWP → key combination "Delivery Type" → Delivery Type = LF, Medium = 8, Time = 4, Language = EN, Partner function SP. Save.

# 6. Testing

- Create a sales order.

- Create a delivery via VL01N referencing that sales order. Save the delivery without PGI.

- Output ZBWP fires automatically on save. Verify in VL03N → Extras → Delivery Output → Header. Determination Analysis on the same screen explains any misses.

- The CPI monitor shows the incoming DeliveryReplication and the downstream Amazon MCF response.

- Amazon MCF returns a fulfillmentOrder whose orderId = \<SalesOrder\>.\<Delivery\>, status PROCESSING.

## 6.1 Reference test result (Path B)

Sales order 0000022035 was created; delivery 0080018896 was created via VL01N and saved without PGI. Output type ZBWP fired automatically on save. HTTP 202 was returned from CPI. Amazon MCF returned:

\<order\>\
\<orderId\>0000022035.0080018896\</orderId\>\
\<status\>PROCESSING\</status\>\
\<fulfillmentConfiguration\>\
\<action\>SHIP\</action\>\
\<policy\>FILL_ALL_AVAILABLE\</policy\>\
\</fulfillmentConfiguration\>\
\<lineItems\>\
\<lineItemId\>000010\</lineItemId\>\
\<product\>\<productIdentifier\>\<amazonSku\>TG61\</amazonSku\>\</productIdentifier\>\</product\>\
\<amount\>\<unit\>EACHES\</unit\>\<value\>1.000\</value\>\</amount\>\
\</lineItems\>\
\</order\>
