import com.sap.gateway.ip.core.customdev.util.Message
import groovy.util.XmlSlurper
import groovy.json.JsonOutput

/**
 * Phase 1: Parse BAPI_DELIVERY_GETLIST.Response, extract delivery data,
 * store in properties, and build BAPISDORDER_GETDETAILEDLIST request to fetch ship-to address.
 */
Message extractDeliveryData(Message message) {
    def body = message.getBody(java.lang.String) as String
    def root = new XmlSlurper(false, false).parseText(body)

    // Store original payload for error handling
    message.setProperty("originalPayload", body)

    // Extract delivery header
    def header = root.ET_DELIVERY_HEADER.item[0]
    def deliveryNumber = header.VBELN.text()?.trim()
    message.setProperty("deliveryNumber", deliveryNumber)

    // Extract items - get sales order from first item's VGBEL
    def items = root.ET_DELIVERY_ITEM.item
    def salesOrder = items[0]?.VGBEL?.text()?.trim() ?: ""
    message.setProperty("salesOrder", salesOrder)
    message.setProperty("salesOrderPadded", salesOrder.padLeft(10, '0'))

    // Store items as comma-separated: POSNR|MATNR|LFIMG per item
    def itemData = []
    items.each { item ->
        def posnr = item.POSNR.text()?.trim()
        def matnr = item.MATNR.text()?.trim()
        def lfimg = item.LFIMG.text()?.trim()
        def vgpos = item.VGPOS.text()?.trim()
        if (posnr && matnr) {
            itemData.add("${posnr}|${matnr}|${lfimg}|${vgpos}")
        }
    }
    message.setProperty("deliveryItems", itemData.join(";"))

    // Find WE (ship-to) partner and get ADRNR
    def wePartner = root.ET_DELIVERY_PARTNER.item.find { it.PARVW.text() == "WE" }
    def adrnr = wePartner?.ADRNR?.text()?.trim() ?: ""
    message.setProperty("shipToAddressNumber", adrnr)

    // Build BAPISDORDER_GETDETAILEDLIST request to fetch address
    def xml = '<rfc:BAPISDORDER_GETDETAILEDLIST xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n' +
              '  <I_BAPI_VIEW>\n' +
              '    <HEADER>X</HEADER>\n' +
              '    <PARTNER>X</PARTNER>\n' +
              '    <ADDRESS>X</ADDRESS>\n' +
              '  </I_BAPI_VIEW>\n' +
              '  <SALES_DOCUMENTS>\n' +
              '    <item>\n' +
              "      <VBELN>${salesOrder}</VBELN>\n" +
              '    </item>\n' +
              '  </SALES_DOCUMENTS>\n' +
              '</rfc:BAPISDORDER_GETDETAILEDLIST>'

    message.setBody(xml)
    return message
}

/**
 * Phase 2: Parse BAPISDORDER_GETDETAILEDLIST.Response, match address by ADRNR,
 * and build SP-API createFulfillmentOrder JSON request.
 */
Message buildCreateOrderRequest(Message message) {
    def body = message.getBody(java.lang.String) as String
    def root = new XmlSlurper(false, false).parseText(body)
    def map = message.getProperties()

    def deliveryNumber = map.get("deliveryNumber") ?: ""
    def salesOrder = map.get("salesOrder") ?: ""
    def adrnr = map.get("shipToAddressNumber") ?: ""
    def itemsStr = map.get("deliveryItems") ?: ""

    // Find address matching the ship-to ADRNR
    def addressRow = root.ORDER_ADDRESS_OUT.item.find { it.ADDRESS.text()?.trim() == adrnr }

    def recipientName = addressRow?.NAME?.text() ?: ""
    def street = addressRow?.STREETNA?.text() ?: addressRow?.STREET?.text() ?: ""
    def city = addressRow?.CITY?.text() ?: ""
    def region = addressRow?.REGION?.text() ?: ""
    def postalCode = addressRow?.POSTL_CODE?.text() ?: ""
    def countryCode = addressRow?.COUNTRYISO?.text() ?: addressRow?.COUNTRY?.text() ?: "US"
    def phone = addressRow?.TELEPHONE?.text() ?: ""

    // Build line items from stored delivery items
    def lineItems = []
    if (itemsStr) {
        itemsStr.split(";").each { entry ->
            def parts = entry.split("\\|")
            if (parts.length >= 4) {
                lineItems.add([
                    lineItemId: parts[3],  // VGPOS (sales order item)
                    product: [
                        productIdentifier: [
                            amazonSku: parts[1]  // MATNR
                        ]
                    ],
                    amount: [
                        unit: "EACHES",
                        value: parts[2] ?: "1.0"  // LFIMG
                    ]
                ])
            }
        }
    }

    // orderId = salesOrder.deliveryNumber
    def orderId = "${salesOrder}.${deliveryNumber}"
    def displayOrderId = salesOrder

    // Build SP-API createFulfillmentOrder request (v2026-07-04)
    def deliveryAddress = [
        name: recipientName,
        addressLine1: street,
        city: city,
        stateOrRegion: region,
        postalCode: postalCode,
        countryCode: countryCode
    ]
    if (phone) deliveryAddress.phone = phone

    def jsonMap = [
        orderId: orderId,
        displayableOrderId: displayOrderId,
        fulfillmentConfiguration: [
            serviceLevel: [ serviceTiers: ["STANDARD"] ],
            action: "SHIP",
            policy: "FILL_ALL_AVAILABLE"
        ],
        destination: [
            deliveryAddress: deliveryAddress
        ],
        origin: [
            countryCode: countryCode
        ],
        lineItems: lineItems
    ]

    def jsonOutput = JsonOutput.toJson(jsonMap)

    message.setProperty("spApiRequestBody", jsonOutput)
    message.setBody(jsonOutput)
    message.setHeader("Content-Type", "application/json")

    return message
}

/**
 * Legacy entry point - kept for backward compatibility.
 * Now calls extractDeliveryData as the first phase.
 */
Message processData(Message message) {
    return extractDeliveryData(message)
}
