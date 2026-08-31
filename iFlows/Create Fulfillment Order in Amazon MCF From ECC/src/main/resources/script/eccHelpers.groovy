import com.sap.gateway.ip.core.customdev.util.Message

/**
 * Builds RFC_SAVE_TEXT request to mark delivery as processed.
 * Writes the Amazon orderId to the delivery header text.
 */
Message buildSaveTextRequest(Message message) {
    def map = message.getProperties()
    def deliveryNumber = map.get("deliveryNumber") ?: ""
    def salesOrder = map.get("salesOrder") ?: ""
    def longTextId = map.get("longTextId") ?: "0001"
    def orderId = "${salesOrder}.${deliveryNumber}"

    def xml = '<rfc:RFC_SAVE_TEXT xmlns:rfc="urn:sap-com:document:sap:rfc:functions">\n' +
              '  <HEADER>\n' +
              '    <TDOBJECT>VBBK</TDOBJECT>\n' +
              "    <TDNAME>${deliveryNumber}</TDNAME>\n" +
              "    <TDID>${longTextId}</TDID>\n" +
              '    <TDSPRAS>E</TDSPRAS>\n' +
              '  </HEADER>\n' +
              '  <TEXT_LINES>\n' +
              '    <item>\n' +
              '      <TDOBJECT>VBBK</TDOBJECT>\n' +
              "      <TDNAME>${deliveryNumber}</TDNAME>\n" +
              "      <TDID>${longTextId}</TDID>\n" +
              '      <TDSPRAS>E</TDSPRAS>\n' +
              '      <TDFORMAT>*</TDFORMAT>\n' +
              "      <TDLINE>OrderId: ${orderId}</TDLINE>\n" +
              '    </item>\n' +
              '  </TEXT_LINES>\n' +
              '</rfc:RFC_SAVE_TEXT>'

    message.setBody(xml)
    return message
}
