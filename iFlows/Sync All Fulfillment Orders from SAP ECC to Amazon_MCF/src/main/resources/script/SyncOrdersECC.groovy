import com.sap.gateway.ip.core.customdev.util.Message
import groovy.util.XmlSlurper

/**
 * Builds BAPI_DELIVERY_GETLIST request to fetch deliveries by shipping point and date range.
 */
def Message buildGetDeliveryListRequest(Message message) {
    def map = message.getProperties()
    def shippingPoint = map.get("shippingPoint") ?: "1201"
    def creationDateFrom = map.get("creationDateFrom") ?: "19990101"
    def creationDateTo = map.get("creationDateTo") ?: "99991231"

    def xml = '<rfc:BAPI_DELIVERY_GETLIST xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n' +
              '  <IS_DLV_DATA_CONTROL>\n' +
              '    <HEAD_STATUS>X</HEAD_STATUS>\n' +
              '    <HEAD_PARTNER>X</HEAD_PARTNER>\n' +
              '    <ITEM>X</ITEM>\n' +
              '    <ITEM_STATUS>X</ITEM_STATUS>\n' +
              '    <DOC_FLOW>X</DOC_FLOW>\n' +
              '  </IS_DLV_DATA_CONTROL>\n' +
              '  <IT_VSTEL>\n' +
              '    <item>\n' +
              '      <SIGN>I</SIGN>\n' +
              '      <OPTION>EQ</OPTION>\n' +
              "      <SHIP_POINT_LOW>${shippingPoint}</SHIP_POINT_LOW>\n" +
              '    </item>\n' +
              '  </IT_VSTEL>\n' +
              '  <IT_ERDAT>\n' +
              '    <item>\n' +
              '      <SIGN>I</SIGN>\n' +
              '      <OPTION>BT</OPTION>\n' +
              "      <CR_ON_LOW>${creationDateFrom}</CR_ON_LOW>\n" +
              "      <CR_ON_HIGH>${creationDateTo}</CR_ON_HIGH>\n" +
              '    </item>\n' +
              '  </IT_ERDAT>\n' +
              '  <IT_PKSTK>\n' +
              '    <item>\n' +
              '      <SIGN>I</SIGN>\n' +
              '      <OPTION>EQ</OPTION>\n' +
              '      <PKSTK_LOW>A</PKSTK_LOW>\n' +
              '    </item>\n' +
              '  </IT_PKSTK>\n' +
              '</rfc:BAPI_DELIVERY_GETLIST>'

    message.setBody(xml)
    return message
}

/**
 * Parses BAPI_DELIVERY_GETLIST response and extracts delivery numbers (VBELN).
 */
def Message parseDeliveryListResponse(Message message) {
    def body = message.getBody(String.class)
    def root = new XmlSlurper(false, false).parseText(body)

    def deliveries = []
    root.ET_DELIVERY_HEADER.item.each { item ->
        def vbeln = item.VBELN.text()?.trim()
        if (vbeln) {
            deliveries.add(vbeln)
        }
    }

    message.setProperty("orderList", deliveries.join(","))
    message.setProperty("orderCount", deliveries.size().toString())
    return message
}

/**
 * Gets current delivery from the comma-separated list by index.
 */
def Message getCurrentDelivery(Message message) {
    def orderList = (message.getProperty("orderList") ?: "").split(",")
    def index = Integer.parseInt(message.getProperty("loopIndex") ?: "0")
    if (index < orderList.size()) {
        message.setProperty("currentOrder", orderList[index])
    }
    message.setProperty("loopIndex", (index + 1).toString())
    return message
}

/**
 * Builds RFC_READ_TEXT request to check if delivery header text exists (idempotency).
 */
def Message buildCheckProcessedTextRequest(Message message) {
    def vbeln = message.getProperty("currentOrder") ?: ""
    def textId = message.getProperties().get("longTextId") ?: "0001"

    def xml = '<rfc:RFC_READ_TEXT xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n' +
              '  <TEXT_LINES>\n' +
              '    <item>\n' +
              '      <TDOBJECT>VBBK</TDOBJECT>\n' +
              "      <TDNAME>${vbeln}</TDNAME>\n" +
              "      <TDID>${textId}</TDID>\n" +
              '      <TDSPRAS>E</TDSPRAS>\n' +
              '      <TDFORMAT></TDFORMAT>\n' +
              '      <TDLINE></TDLINE>\n' +
              '    </item>\n' +
              '  </TEXT_LINES>\n' +
              '</rfc:RFC_READ_TEXT>'

    message.setBody(xml)
    return message
}

/**
 * Parses RFC_READ_TEXT response. If TDLINE has content, sets skipOrder=true.
 * If not processed, builds BAPI_DELIVERY_GETLIST for the specific delivery.
 */
def Message parseTextAndBuildDeliveryRequest(Message message) {
    def body = message.getBody(String.class)
    def root = new XmlSlurper(false, false).parseText(body)

    def hasText = root.TEXT_LINES.item.any { item ->
        item.TDLINE.text()?.trim()
    }

    if (hasText) {
        message.setProperty("skipOrder", "true")
        return message
    }

    def vbeln = message.getProperty("currentOrder") ?: ""

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
              "      <DELIV_NUMB_LOW>${vbeln}</DELIV_NUMB_LOW>\n" +
              '    </item>\n' +
              '  </IT_VBELN>\n' +
              '</rfc:BAPI_DELIVERY_GETLIST>'

    message.setProperty("skipOrder", "false")
    message.setBody(xml)
    return message
}
