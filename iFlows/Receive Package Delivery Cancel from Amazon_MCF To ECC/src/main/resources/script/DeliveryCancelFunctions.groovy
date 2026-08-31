import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

/**
 * Processes the Fulfillment Outbound API 2020-07-01 FulfillmentOrderStatusNotification
 * for an Order event (EventType = "Order").
 *
 * Extracts the sales order and delivery doc from SellerFulfillmentOrderId
 * (pattern: <SalesOrder>.<DeliveryDoc>).
 *
 * Status handling (the notification carries no line items or quantities):
 *   - "Cancelled"         -> full order cancellation (all lines / all units); proceed.
 *   - "COMPLETE_PARTIAL"  -> partial cancellation; NOT supported -> throw.
 *   - anything else       -> unexpected status -> throw.
 */
def Message processData(Message message) {
    def body = message.getBody(String.class)
    def json = new JsonSlurper().parseText(body)

    def notification = json?.FulfillmentOrderStatusNotification
    if (notification == null) {
        throw new Exception("Missing FulfillmentOrderStatusNotification in cancel event payload")
    }

    def orderId = notification?.SellerFulfillmentOrderId ?: ""
    def orderStatus = notification?.FulfillmentOrderStatus ?: ""

    if (!orderId?.trim()) {
        throw new Exception("SellerFulfillmentOrderId is missing from the cancel event")
    }

    // Amazon-side decision: only a full cancellation is processed. A partial
    // cancellation must stop here (we do not support splitting a delivery).
    if (orderStatus == "COMPLETE_PARTIAL") {
        throw new Exception("Fulfillment order ${orderId} is COMPLETE_PARTIAL. Partial cancellation not supported.")
    }
    if (orderStatus != "Cancelled") {
        throw new Exception("Unexpected FulfillmentOrderStatus '${orderStatus}' for order ${orderId}. Expected 'Cancelled'.")
    }

    def parts = orderId.split('\\.')
    if (parts.length < 2) {
        throw new Exception("Invalid SellerFulfillmentOrderId format '${orderId}' - expected pattern: <SalesOrder>.<DeliveryDoc>")
    }

    def salesOrder = parts[0].padLeft(10, '0')
    def deliveryDoc = parts[1].padLeft(10, '0')

    message.setProperty("originalPayload", body)
    message.setProperty("orderId", orderId)
    message.setProperty("salesOrder", salesOrder)
    message.setProperty("deliveryDocumentNumber", deliveryDoc)
    message.setProperty("orderStatus", orderStatus)
    message.setHeader("SAP_ApplicationID", orderId)

    return message
}

/**
 * Builds BAPI_DELIVERY_GETLIST RFC XML to fetch delivery doc status.
 */
def Message buildGetDeliveryListRequest(Message message) {
    def deliveryDoc = (message.getProperty("deliveryDocumentNumber") ?: "").padLeft(10, '0')

    def xml = '<rfc:BAPI_DELIVERY_GETLIST xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n' +
              '  <IS_DLV_DATA_CONTROL>\n' +
              '    <HEAD_STATUS>X</HEAD_STATUS>\n' +
              '    <HEAD_PARTNER>X</HEAD_PARTNER>\n' +
              '    <ITEM>X</ITEM>\n' +
              '    <ITEM_STATUS>X</ITEM_STATUS>\n' +
              '    <DOC_FLOW>X</DOC_FLOW>\n' +
              '  </IS_DLV_DATA_CONTROL>\n' +
              '  <IT_VBELN>\n' +
              '    <item>\n' +
              '      <SIGN>I</SIGN>\n' +
              '      <OPTION>EQ</OPTION>\n' +
              "      <DELIV_NUMB_LOW>${deliveryDoc}</DELIV_NUMB_LOW>\n" +
              '    </item>\n' +
              '  </IT_VBELN>\n' +
              '</rfc:BAPI_DELIVERY_GETLIST>'

    message.setBody(xml)
    return message
}

/**
 * Parses BAPI_DELIVERY_GETLIST response.
 *
 * ECC-side guard — checks GBSTK in ET_DELIVERY_HEADER_STS:
 *   'A' = delivery not yet processed → can cancel (zero out qty)
 *   Anything else = delivery already processed/shipped → throw exception
 *
 * The Amazon-side partial-cancel decision is already handled in processData
 * (status must be "Cancelled"). Since a full cancellation covers every line,
 * this method captures the delivery's line items from the response and stores
 * them (as a compact "POSNR;POSNR" string) in property "deliveryLines" so the
 * zero-out step can delete every line.
 */
def Message parseDeliveryStatusAndValidate(Message message) {
    def body = message.getBody(String.class)
    def deliveryDoc = message.getProperty("deliveryDocumentNumber") ?: ""

    // ECC-side guard: GBSTK from ET_DELIVERY_HEADER_STS
    def stsMatch = body =~ /<ET_DELIVERY_HEADER_STS>([\s\S]*?)<\/ET_DELIVERY_HEADER_STS>/
    if (!stsMatch.find()) {
        throw new Exception("No ET_DELIVERY_HEADER_STS found in response for delivery ${deliveryDoc}")
    }
    def stsBlock = stsMatch.group(1)
    def gbstkMatch = stsBlock =~ /<GBSTK>(.*?)<\/GBSTK>/
    def gbstk = gbstkMatch.find() ? gbstkMatch.group(1)?.trim() : ""

    if (gbstk != "A") {
        throw new Exception("Delivery ${deliveryDoc} already processed (GBSTK=${gbstk}). Cannot cancel.")
    }

    // Capture all delivery line item numbers from the response
    def deliveryLineNumbers = []
    def itemTableMatch = body =~ /<ET_DELIVERY_ITEM>([\s\S]*?)<\/ET_DELIVERY_ITEM>/
    if (itemTableMatch.find()) {
        def itemTableBlock = itemTableMatch.group(1)
        def itemMatcher = itemTableBlock =~ /<item>([\s\S]*?)<\/item>/
        while (itemMatcher.find()) {
            def posnr = (itemMatcher.group(1) =~ /<POSNR>(.*?)<\/POSNR>/)
            if (posnr.find()) {
                def lineNum = posnr.group(1)?.trim()
                if (lineNum) {
                    deliveryLineNumbers.add(lineNum)
                }
            }
        }
    }

    if (deliveryLineNumbers.isEmpty()) {
        throw new Exception("No item lines found in delivery document ${deliveryDoc} response")
    }

    message.setProperty("deliveryLines", deliveryLineNumbers.join(";"))
    return message
}

/**
 * Builds WS_DELIVERY_UPDATE RFC XML to zero out delivery lines.
 * Sets LIPS_DEL=X on each delivery line item (from the GETLIST response,
 * stored in property "deliveryLines"). A full cancellation deletes every line.
 */
def Message buildZeroDeliveryQty(Message message) {
    def deliveryDoc = (message.getProperty("deliveryDocumentNumber") ?: "").padLeft(10, '0')
    def serialized = (message.getProperty("deliveryLines") ?: "") as String
    def deliveryLines = serialized.trim().isEmpty() ? [] : serialized.split(";").collect { it.trim() }.findAll { it }

    def xmlOutput = new StringBuilder()
    xmlOutput.append('<rfc:WS_DELIVERY_UPDATE xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n')
    xmlOutput.append("  <DELIVERY>${deliveryDoc}</DELIVERY>\n")
    xmlOutput.append('  <SYNCHRON>X</SYNCHRON>\n')
    xmlOutput.append('  <IF_DATABASE_UPDATE_1>1</IF_DATABASE_UPDATE_1>\n')
    xmlOutput.append('  <VBKOK_WA>\n')
    xmlOutput.append("    <VBELN_VL>${deliveryDoc}</VBELN_VL>\n")
    xmlOutput.append("    <VBELN>${deliveryDoc}</VBELN>\n")
    xmlOutput.append('  </VBKOK_WA>\n')
    xmlOutput.append('  <VBPOK_TAB>\n')
    deliveryLines.each { lineItemId ->
        xmlOutput.append('    <item>\n')
        xmlOutput.append("      <VBELN_VL>${deliveryDoc}</VBELN_VL>\n")
        xmlOutput.append("      <POSNR_VL>${lineItemId}</POSNR_VL>\n")
        xmlOutput.append("      <VBELN>${deliveryDoc}</VBELN>\n")
        xmlOutput.append("      <POSNN>${lineItemId}</POSNN>\n")
        xmlOutput.append('      <LIPS_DEL>X</LIPS_DEL>\n')
        xmlOutput.append('    </item>\n')
    }
    xmlOutput.append('  </VBPOK_TAB>\n')
    xmlOutput.append('</rfc:WS_DELIVERY_UPDATE>')

    message.setBody(xmlOutput.toString())
    return message
}
