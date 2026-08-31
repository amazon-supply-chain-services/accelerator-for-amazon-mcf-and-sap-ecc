import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Processes the Fulfillment Outbound API 2020-07-01 FulfillmentOrderStatusNotification
 * for a Shipment event (EventType = "Shipment").
 *
 * Extracts the delivery doc number from SellerFulfillmentOrderId (pattern:
 * <SalesOrder>.<DeliveryDoc>) and the carrier/tracking from the first shipment
 * package, then builds a BAPI_DELIVERY_GETLIST request to fetch the existing
 * delivery document.
 *
 * Status handling (the notification carries no line items or quantities):
 *   - "Complete"          -> all lines shipped; proceed. Quantities are taken
 *                            from the BAPI_DELIVERY_GETLIST response downstream.
 *   - "COMPLETE_PARTIAL"  -> partial shipment; NOT supported -> throw.
 *   - "PROCESSING"        -> multiple shipments in progress; NOT supported -> throw.
 *   - anything else       -> unexpected status -> throw.
 *
 * Input:  2020-07-01 FulfillmentOrderStatusNotification JSON (Shipment event)
 * Output: RFC XML for BAPI_DELIVERY_GETLIST
 */
def Message processData(Message message) {
    def body = message.getProperty("originalPayload")
    def json = new JsonSlurper().parseText(body)
    def messageLog = messageLogFactory.getMessageLog(message)

    def notification = json?.FulfillmentOrderStatusNotification
    if (notification == null) {
        throw new Exception("Missing FulfillmentOrderStatusNotification in event payload")
    }

    def orderId = notification?.SellerFulfillmentOrderId ?: ""
    def orderStatus = notification?.FulfillmentOrderStatus ?: ""

    // Only a fully shipped order is processed. A partial or in-progress order
    // must stop here (we do not support splitting a delivery).
    if (orderStatus == "COMPLETE_PARTIAL") {
        throw new Exception("Fulfillment order ${orderId} is COMPLETE_PARTIAL. Partial shipment not supported.")
    }
    if (orderStatus == "PROCESSING") {
        throw new Exception("Fulfillment order ${orderId} is PROCESSING (multiple shipments). Not supported.")
    }
    if (orderStatus != "Complete") {
        throw new Exception("Unexpected FulfillmentOrderStatus '${orderStatus}' for order ${orderId}. Expected 'Complete'.")
    }

    // Extract delivery doc number from SellerFulfillmentOrderId: <SalesOrder>.<DeliveryDoc>
    def parts = orderId.split('\\.')
    if (parts.length < 2) {
        throw new Exception("Invalid SellerFulfillmentOrderId format '${orderId}' - expected pattern: <SalesOrder>.<DeliveryDoc>")
    }
    def deliveryDocNum = parts[1].padLeft(10, '0')

    def shipment = notification?.FulfillmentShipment ?: [:]
    def amazonShipmentId = shipment?.AmazonShipmentId ?: ""
    def shipmentStatus = shipment?.FulfillmentShipmentStatus ?: ""
    def shipmentPackages = shipment?.FulfillmentShipmentPackages ?: []

    def trackingNumber = ""
    def carrierCode = ""
    if (!shipmentPackages.isEmpty()) {
        def firstPackage = shipmentPackages[0]
        trackingNumber = firstPackage?.TrackingNumber ?: ""
        carrierCode = firstPackage?.CarrierCode ?: ""
    }

    // Build RFC XML for BAPI_DELIVERY_GETLIST to fetch existing delivery doc
    def xmlOutput = new StringBuilder()
    xmlOutput.append('<rfc:BAPI_DELIVERY_GETLIST xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n')
    xmlOutput.append('  <IS_DLV_DATA_CONTROL>\n')
    xmlOutput.append('    <HEAD_STATUS>X</HEAD_STATUS>\n')
    xmlOutput.append('    <HEAD_PARTNER>X</HEAD_PARTNER>\n')
    xmlOutput.append('    <ITEM>X</ITEM>\n')
    xmlOutput.append('    <ITEM_STATUS>X</ITEM_STATUS>\n')
    xmlOutput.append('    <DOC_FLOW>X</DOC_FLOW>\n')
    xmlOutput.append('  </IS_DLV_DATA_CONTROL>\n')
    xmlOutput.append('  <IT_VBELN>\n')
    xmlOutput.append('    <item>\n')
    xmlOutput.append('      <SIGN>I</SIGN>\n')
    xmlOutput.append('      <OPTION>EQ</OPTION>\n')
    xmlOutput.append("      <DELIV_NUMB_LOW>${deliveryDocNum}</DELIV_NUMB_LOW>\n")
    xmlOutput.append('    </item>\n')
    xmlOutput.append('  </IT_VBELN>\n')
    xmlOutput.append('</rfc:BAPI_DELIVERY_GETLIST>')

    message.setBody(xmlOutput.toString())

    // Set properties for downstream processing
    message.setProperty("orderId", orderId)
    message.setProperty("expectedDeliveryDoc", deliveryDocNum)
    message.setProperty("amazonShipmentId", amazonShipmentId)
    message.setProperty("shipmentStatus", shipmentStatus)
    message.setProperty("orderState", "SHIPPED")
    message.setProperty("trackingNumber", trackingNumber)
    message.setProperty("carrierCode", carrierCode)
    message.setHeader("SAP_ApplicationID", orderId)
    messageLog.addCustomHeaderProperty("orderState", "SHIPPED")
    messageLog.addCustomHeaderProperty("amazonShipmentId", amazonShipmentId)

    // Date/time properties for Update Delivery Date step (today)
    def now = LocalDateTime.now()
    def currentDate = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    def currentDateOnly = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "T00:00:00"
    def currentTimeOnly = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    message.setProperty("plannedGIDate", currentDateOnly)
    message.setProperty("plannedGITime", currentTimeOnly)
    message.setProperty("pickingDate", currentDateOnly)
    message.setProperty("pickingTime", currentTimeOnly)

    def tetText = """Date: ${currentDate}
        ShipmentId: ${amazonShipmentId}
        Status: ${shipmentStatus}
        Tracking ID: ${trackingNumber}
        Carrier Code: ${carrierCode}"""

    message.setProperty("TETTX04", tetText)

    return message
}


/**
 * Parses the BAPI_DELIVERY_GETLIST response and captures the delivery's line
 * items and quantities. Because the 2020-07-01 Shipment notification carries no
 * line items or quantities, the ECC delivery document is the source of truth for
 * what to pick and post Goods Issue against.
 *
 * The order was already confirmed "Complete" in processData, so every delivery
 * line is picked/shipped in full. The parsed lines are stored (as a compact
 * "POSNR=LFIMG;..." string) in property "deliveryLines" for the picking and
 * Goods Issue builders.
 */
def Message validatePartialFulfillment(Message message) {
    def body = message.getBody(String.class)
    def deliveryDoc = message.getProperty("expectedDeliveryDoc") ?: ""

    // Extract item block from ET_DELIVERY_ITEM table only
    def tableMatch = body =~ /<ET_DELIVERY_ITEM>([\s\S]*?)<\/ET_DELIVERY_ITEM>/
    def itemTableBlock = tableMatch.find() ? tableMatch.group(1) : ""

    // Build ordered map of delivery doc line quantities from the response
    def deliveryLineQtys = [:]
    def itemMatcher = itemTableBlock =~ /<item>([\s\S]*?)<\/item>/
    while (itemMatcher.find()) {
        def itemBlock = itemMatcher.group(1)
        def posnr = (itemBlock =~ /<POSNR>(.*?)<\/POSNR>/)
        def qty = (itemBlock =~ /<LFIMG>(.*?)<\/LFIMG>/)
        if (posnr.find() && qty.find()) {
            def lineNum = posnr.group(1)?.trim()
            def lineQty = qty.group(1)?.trim()
            if (lineNum && lineQty) {
                deliveryLineQtys[lineNum] = lineQty
            }
        }
    }

    if (deliveryLineQtys.isEmpty()) {
        throw new Exception("No item lines found in delivery document ${deliveryDoc} response")
    }

    // Serialize the line->qty map as "POSNR=LFIMG;POSNR=LFIMG" for downstream steps.
    def serialized = deliveryLineQtys.collect { k, v -> "${k}=${v}" }.join(";")

    message.setProperty("deliveryLines", serialized)
    message.setProperty("deliveryDocumentNumber", deliveryDoc)
    message.setHeader("SAP_ApplicationID", deliveryDoc)
    return message
}

/**
 * Parses the "deliveryLines" property (POSNR=LFIMG;...) into an ordered map.
 */
private static Map parseDeliveryLines(String serialized) {
    def map = [:]
    if (serialized == null || serialized.trim().isEmpty()) {
        return map
    }
    serialized.split(";").each { entry ->
        def kv = entry.split("=", 2)
        if (kv.length == 2 && kv[0].trim()) {
            map[kv[0].trim()] = kv[1].trim()
        }
    }
    return map
}

/**
 * Builds WS_DELIVERY_UPDATE RFC XML to confirm picking for the delivery.
 * VRKME (item unit) from config 'itemUnit'.
 * Lines and picked quantities come from the delivery document (GETLIST), stored
 * in property "deliveryLines" — every line is picked in full.
 */
def Message buildPickAllItemsRequest(Message message) {
    def deliveryDoc = (message.getProperty("deliveryDocumentNumber") ?: "").padLeft(10, '0')
    def vrkme = message.getProperty("itemUnit") ?: "ST"
    def kodat = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    def deliveryLines = parseDeliveryLines(message.getProperty("deliveryLines") as String)

    def xmlOutput = new StringBuilder()
    xmlOutput.append('<rfc:WS_DELIVERY_UPDATE xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n')
    xmlOutput.append("  <DELIVERY>${deliveryDoc}</DELIVERY>\n")
    xmlOutput.append('  <SYNCHRON>X</SYNCHRON>\n')
    xmlOutput.append('  <UPDATE_PICKING>X</UPDATE_PICKING>\n')
    xmlOutput.append('  <IF_DATABASE_UPDATE_1>1</IF_DATABASE_UPDATE_1>\n')
    xmlOutput.append('  <VBKOK_WA>\n')
    xmlOutput.append("    <VBELN_VL>${deliveryDoc}</VBELN_VL>\n")
    xmlOutput.append("    <VBELN>${deliveryDoc}</VBELN>\n")
    xmlOutput.append("    <KODAT>${kodat}</KODAT>\n")
    xmlOutput.append('  </VBKOK_WA>\n')
    xmlOutput.append('  <VBPOK_TAB>\n')
    deliveryLines.each { itemNumber, quantity ->
        xmlOutput.append('    <item>\n')
        xmlOutput.append("      <VBELN_VL>${deliveryDoc}</VBELN_VL>\n")
        xmlOutput.append("      <POSNR_VL>${itemNumber}</POSNR_VL>\n")
        xmlOutput.append("      <VBELN>${deliveryDoc}</VBELN>\n")
        xmlOutput.append("      <POSNN>${itemNumber}</POSNN>\n")
        xmlOutput.append("      <PIKMG>${quantity}</PIKMG>\n")
        xmlOutput.append("      <VRKME>${vrkme}</VRKME>\n")
        xmlOutput.append('      <TAQUI>X</TAQUI>\n')
        xmlOutput.append('    </item>\n')
    }
    xmlOutput.append('  </VBPOK_TAB>\n')
    xmlOutput.append('</rfc:WS_DELIVERY_UPDATE>')

    message.setBody(xmlOutput.toString())
    return message
}

/**
 * Builds BAPI_OUTB_DELIVERY_CHANGE RFC XML to update delivery date/time.
 * Uses HEADER_DEADLINES with TIMETYPE WSHDRWADAT (Planned GI) and WSHDRKODAT (Picking).
 * Commit via Transaction Commit checked on RFC adapter (one-shot).
 */
def Message buildUpdateDeliveryDate(Message message) {
    def deliveryDoc = (message.getProperty("deliveryDocumentNumber") ?: "").padLeft(10, '0')
    // Build UTC timestamp in format YYYYMMDDHHmmss (today)
    def timestamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

    def xmlOutput = new StringBuilder()
    xmlOutput.append('<rfc:BAPI_OUTB_DELIVERY_CHANGE xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n')
    xmlOutput.append("  <DELIVERY>${deliveryDoc}</DELIVERY>\n")
    xmlOutput.append('  <HEADER_DATA>\n')
    xmlOutput.append("    <DELIV_NUMB>${deliveryDoc}</DELIV_NUMB>\n")
    xmlOutput.append('  </HEADER_DATA>\n')
    xmlOutput.append('  <HEADER_CONTROL>\n')
    xmlOutput.append("    <DELIV_NUMB>${deliveryDoc}</DELIV_NUMB>\n")
    xmlOutput.append('  </HEADER_CONTROL>\n')
    xmlOutput.append('  <HEADER_DEADLINES>\n')
    xmlOutput.append('    <item>\n')
    xmlOutput.append("      <DELIV_NUMB>${deliveryDoc}</DELIV_NUMB>\n")
    xmlOutput.append('      <TIMETYPE>WSHDRWADAT</TIMETYPE>\n')
    xmlOutput.append("      <TIMESTAMP_UTC>${timestamp}</TIMESTAMP_UTC>\n")
    xmlOutput.append('    </item>\n')
    xmlOutput.append('    <item>\n')
    xmlOutput.append("      <DELIV_NUMB>${deliveryDoc}</DELIV_NUMB>\n")
    xmlOutput.append('      <TIMETYPE>WSHDRKODAT</TIMETYPE>\n')
    xmlOutput.append("      <TIMESTAMP_UTC>${timestamp}</TIMESTAMP_UTC>\n")
    xmlOutput.append('    </item>\n')
    xmlOutput.append('  </HEADER_DEADLINES>\n')
    xmlOutput.append('</rfc:BAPI_OUTB_DELIVERY_CHANGE>')

    message.setBody(xmlOutput.toString())
    return message
}

/**
 * Builds RFC_SAVE_TEXT RFC XML to create delivery header text.
 * TDID from config 'shippingNotifications'. TDNAME from delivery doc.
 * TDSPRAS hardcoded 'E'. TDLINE mapped from shipment properties.
 */
def Message buildCreateDeliveryText_SaveText(Message message) {
    def deliveryDoc = (message.getProperty("deliveryDocumentNumber") ?: "").padLeft(10, '0')
    def textId = message.getProperty("shippingNotifications") ?: "0001"
    def shipmentId = message.getProperty("amazonShipmentId") ?: ""
    def shipmentStatus = message.getProperty("shipmentStatus") ?: ""
    def carrierCode = message.getProperty("carrierCode") ?: ""
    def trackingNumber = message.getProperty("trackingNumber") ?: ""

    def xml = '<rfc:RFC_SAVE_TEXT xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n' +
              '  <HEADER>\n' +
              '    <TDOBJECT>VBBK</TDOBJECT>\n' +
              "    <TDNAME>${deliveryDoc}</TDNAME>\n" +
              "    <TDID>${textId}</TDID>\n" +
              '    <TDSPRAS>E</TDSPRAS>\n' +
              '  </HEADER>\n' +
              '  <TEXT_LINES>\n' +
              '    <item>\n' +
              '      <TDOBJECT>VBBK</TDOBJECT>\n' +
              "      <TDNAME>${deliveryDoc}</TDNAME>\n" +
              "      <TDID>${textId}</TDID>\n" +
              '      <TDSPRAS>E</TDSPRAS>\n' +
              '      <TDFORMAT>*</TDFORMAT>\n' +
              "      <TDLINE>ShipmentId: ${shipmentId} | Status: ${shipmentStatus}</TDLINE>\n" +
              '    </item>\n' +
              '    <item>\n' +
              '      <TDOBJECT>VBBK</TDOBJECT>\n' +
              "      <TDNAME>${deliveryDoc}</TDNAME>\n" +
              "      <TDID>${textId}</TDID>\n" +
              '      <TDSPRAS>E</TDSPRAS>\n' +
              '      <TDFORMAT>*</TDFORMAT>\n' +
              "      <TDLINE>Carrier: ${carrierCode} | TrackingId: ${trackingNumber}</TDLINE>\n" +
              '    </item>\n' +
              '  </TEXT_LINES>\n' +
              '</rfc:RFC_SAVE_TEXT>'
    message.setBody(xml)
    return message
}



/**
 * Builds WS_DELIVERY_UPDATE RFC XML to post Goods Issue for the delivery.
 * Sets WABUC=X to trigger PGI, WADAT_IST to today's date.
 * Lines and quantities come from the delivery document (GETLIST), stored in
 * property "deliveryLines" — every line is posted in full.
 */
def Message buildPostGoodsIssue(Message message) {
    def deliveryDoc = (message.getProperty("deliveryDocumentNumber") ?: "").padLeft(10, '0')
    def vrkme = message.getProperty("itemUnit") ?: "ST"
    def today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    def deliveryLines = parseDeliveryLines(message.getProperty("deliveryLines") as String)

    def xmlOutput = new StringBuilder()
    xmlOutput.append('<rfc:WS_DELIVERY_UPDATE xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n')
    xmlOutput.append("  <DELIVERY>${deliveryDoc}</DELIVERY>\n")
    xmlOutput.append('  <SYNCHRON>X</SYNCHRON>\n')
    xmlOutput.append('  <IF_DATABASE_UPDATE_1>1</IF_DATABASE_UPDATE_1>\n')
    xmlOutput.append('  <VBKOK_WA>\n')
    xmlOutput.append("    <VBELN_VL>${deliveryDoc}</VBELN_VL>\n")
    xmlOutput.append("    <VBELN>${deliveryDoc}</VBELN>\n")
    xmlOutput.append('    <WABUC>X</WABUC>\n')
    xmlOutput.append("    <WADAT_IST>${today}</WADAT_IST>\n")
    xmlOutput.append('  </VBKOK_WA>\n')
    xmlOutput.append('  <VBPOK_TAB>\n')
    deliveryLines.each { itemNumber, quantity ->
        xmlOutput.append('    <item>\n')
        xmlOutput.append("      <VBELN_VL>${deliveryDoc}</VBELN_VL>\n")
        xmlOutput.append("      <POSNR_VL>${itemNumber}</POSNR_VL>\n")
        xmlOutput.append("      <VBELN>${deliveryDoc}</VBELN>\n")
        xmlOutput.append("      <POSNN>${itemNumber}</POSNN>\n")
        xmlOutput.append("      <PIKMG>${quantity}</PIKMG>\n")
        xmlOutput.append("      <VRKME>${vrkme}</VRKME>\n")
        xmlOutput.append('    </item>\n')
    }
    xmlOutput.append('  </VBPOK_TAB>\n')
    xmlOutput.append('</rfc:WS_DELIVERY_UPDATE>')

    message.setBody(xmlOutput.toString())
    return message
}
