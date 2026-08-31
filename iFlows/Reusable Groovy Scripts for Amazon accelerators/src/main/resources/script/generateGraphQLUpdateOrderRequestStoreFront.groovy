import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.ITApiFactory
import groovy.util.XmlSlurper

Message processData(Message message) {

    def props = message.getProperties()

    // Externalized configurable values
    def configuredLongTextId = props.get("longTextId")
    def configuredPlant = props.get("plant")
    def sellerID = props.get("SellerID")

    // Get SOAP body
    def body = message.getBody(String)
    if (!body) {
        throw new IllegalStateException("Empty message body")
    }

    def xml = new XmlSlurper().parseText(body)
    def graphQLQuery = ""

    // ==========================
    // 🔹 Normal Sales Order Update
    // ==========================

    // Get OrderId from LongText with configuredLongTextId
    def longTextNode = xml.'**'.find {
        it.name() == 'Text' && it.LongTextID.text()?.trim() == configuredLongTextId
    }
    def orderId = longTextNode?.LongText?.text()?.trim()

    def externalDocId = props.get("aliasId")

    // Determine execution state based on DeliveryBlockReason
    def headerDeliveryBlock = xml.'**'.find { it.name() == 'DeliveryBlockReason' }?.text()?.trim()
    boolean headerBlockHasValue = headerDeliveryBlock && headerDeliveryBlock != ""

    def desiredExecutionState
    if (headerBlockHasValue) {
        desiredExecutionState = "NOT_STARTED"
    } else {
        def items = xml.'**'.findAll { it.name() == 'Item' }
        if (!items || items.isEmpty()) {
            throw new IllegalStateException("No <Item> elements found in the SalesOrder")
        }

        def relevantItems = items.findAll { it.Plant?.text()?.trim() == configuredPlant }
        if (!relevantItems || relevantItems.isEmpty()) {
            throw new IllegalStateException("No <Item> found with Plant='${configuredPlant}'")
        }

        boolean anyBlockHasValue = relevantItems.any { itemNode ->
            def blocks = itemNode.'**'.findAll { it.name() == 'DelivBlockReasonForSchedLine' }
            return blocks.any { it.text()?.trim() }
        }

        desiredExecutionState = anyBlockHasValue ? "NOT_STARTED" : "STARTED"
    }

    // Extract all Items with both SalesOrderItemID and AMAZON_EXTERNAL_ID
    def itemNodes = xml.'**'.findAll { it.name() == 'Item' }
    if (!itemNodes || itemNodes.isEmpty()) {
        throw new IllegalStateException("No <Item> nodes found in XML.")
    }

    def lineItemsBlock = itemNodes.collect { itemNode ->
        def salesOrderItemId = itemNode.SalesOrderItemID?.text()?.trim()
        def amazonExternalId = itemNode.AMAZON_EXTERNAL_ID?.text()?.trim()

        if (!salesOrderItemId) {
            throw new IllegalStateException("Missing SalesOrderItemID for one of the <Item> nodes.")
        }
        if (!amazonExternalId) {
            throw new IllegalStateException("Missing AMAZON_EXTERNAL_ID for SalesOrderItemID '${salesOrderItemId}'.")
        }

        return """{
          id: { lineItemId: "${amazonExternalId}" }
          aliases: [{ aliasType: "EXTERNAL_ID", aliasId: "${salesOrderItemId}" }]
        }"""
    }.join(",")

    // 🔹 Build mutation based on available identifiers
    if (orderId) {
        graphQLQuery = """mutation updateOrder {
  updateOrder(
    orderIdentifier: { orderId: "${orderId}" }
    input: {
      aliases: [{ aliasType: "${sellerID}", aliasId: "${externalDocId}" }]
      desiredExecutionState: ${desiredExecutionState}
      lineItems: [${lineItemsBlock}]
    }
  ) {
    order { id }
  }
}"""
    } else if (externalDocId) {
        graphQLQuery = """mutation updateOrder {
  updateOrder(
    orderIdentifier: {
      alias: {
        aliasType: "${sellerID}"
        aliasId: "${externalDocId}"
      }
    }
    input: {
      aliases: [{ aliasType: "${sellerID}", aliasId: "${externalDocId}" }]
      desiredExecutionState: ${desiredExecutionState}
      lineItems: [${lineItemsBlock}]
    }
  ) {
    order { id }
  }
}"""
    } else {
        throw new IllegalStateException("Neither LongTextID='${configuredLongTextId}' nor aliasId property found in incoming message")
    }

    // Wrap as JSON
    def graphqlJson = JsonOutput.toJson([query: graphQLQuery])
    message.setBody(graphqlJson)
    message.setHeader("Content-Type", "application/json")

    // Secure Store parameters
    String apiTargetIDSecureParameterName = props.get("apiTargetIdSecureParameter")
    String apiAccessKeySecureParameterName = props.get("apiAccessKeySecureParameter")

    def secureStoreService = ITApiFactory.getApi(SecureStoreService.class, null)
    def apiTargetIDSecureParameter = secureStoreService.getUserCredential(apiTargetIDSecureParameterName)
    def apiAccessKeySecureParameter = secureStoreService.getUserCredential(apiAccessKeySecureParameterName)

    if (!apiTargetIDSecureParameter?.getPassword()) {
        throw new IllegalStateException("No secure parameter found with name ${apiTargetIDSecureParameterName} in Secure Store")
    }
    if (!apiAccessKeySecureParameter?.getPassword()) {
        throw new IllegalStateException("No secure parameter found with name ${apiAccessKeySecureParameterName} in Secure Store")
    }

    String apiTargetID = new String(apiTargetIDSecureParameter.getPassword())
    String apiAccessKey = new String(apiAccessKeySecureParameter.getPassword())

    message.setHeader("x-api-access-key", apiAccessKey)
    message.setHeader("X-Omni-TargetId", apiTargetID)
    message.setHeader("x-api-version", props.get("apiVersion"))

    // CPI Debug Attachments
    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addAttachmentAsString("After Mapping Message", graphQLQuery, "text/plain")
    }

    return message
}
