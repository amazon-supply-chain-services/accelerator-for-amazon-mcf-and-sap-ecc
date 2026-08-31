import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.ITApiFactory
import groovy.util.XmlSlurper

Message processData(Message message) {

    def props = message.getProperties()
    def headers = message.getHeaders()

    // Externalized configurable values
    def configuredLongTextId = props.get("longTextId")
    def configuredPlant = props.get("plant")
    def updatePackageFunction = headers.get("updatePackageFunction")?.toString()?.toLowerCase() == "true"

    // Newly externalized parameters
    def sellerID = props.get("SellerID")
   

    // Get SOAP body
    def body = message.getBody(String)
    if (!body) {
        throw new IllegalStateException("Empty message body")
    }

    def xml = new XmlSlurper().parseText(body)
    def graphQLQuery = ""

    // ==========================
    // 🔹 Case 1: Package Update
    // ==========================
    if (updatePackageFunction) {

        def orderId = xml.'**'.find { it.name() == 'orderID' }?.text()?.trim()
        def deliveryDoc = xml.'**'.find { it.name() == 'deliveryDocument' }?.text()?.trim()
        def packageId = xml.'**'.find { it.name() == 'packageId' }?.text()?.trim()

        if (!orderId || !deliveryDoc || !packageId) {
            throw new IllegalStateException("Missing OrderID or DeliveryDocument or PackageID in the incoming Outbound Delivery XML.")
        }

        graphQLQuery = """mutation updateOrder {
  updateOrder(
    orderIdentifier: { orderId: "${orderId}" }
    input: {
      packageInformation: {
        details: [
          {
            aliases: [{ aliasType: "EXTERNAL_ID", aliasId: "${deliveryDoc}" }]
            id: "${packageId}"
          }
        ]
      }
    }
  ) {
    order {
      id
    }
  }
}"""

    // ==========================
    // 🔹 Case 2: Normal Update
    // ==========================
    } else {

        def longTextNode = xml.'**'.find {
            it.name() == 'Text' && it.LongTextID.text()?.trim() == configuredLongTextId
        }
        def orderId = longTextNode?.LongText?.text()?.trim()

        def externalDocId = props.get("aliasId")

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

            boolean anyBlockHasValue = false
            relevantItems.eachWithIndex { itemNode, idx ->
                def blocks = itemNode.'**'.findAll { it.name() == 'DelivBlockReasonForSchedLine' }
                if (blocks.any { it.text()?.trim() }) {
                    anyBlockHasValue = true
                }
            }

            desiredExecutionState = anyBlockHasValue ? "NOT_STARTED" : "STARTED"
        }

        // 🔹 Build mutation based on available identifiers
        if (orderId) {
            graphQLQuery = """mutation updateOrder {
  updateOrder(
    orderIdentifier: { orderId: "${orderId}" }
    input: {
      desiredExecutionState: ${desiredExecutionState}
    }
  ) {
    order {
      id
    }
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
      desiredExecutionState: ${desiredExecutionState}
    }
  ) {
    order {
      id
    }
  }
}"""
        } else {
            throw new IllegalStateException(
                "Neither LongTextID='${configuredLongTextId}' nor aliasId property found in incoming message"
            )
        }
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

    if (apiTargetIDSecureParameter == null || apiTargetIDSecureParameter.getPassword() == null) {
        throw new IllegalStateException("No secure parameter found with name ${apiTargetIDSecureParameterName} in Secure Store")
    }
    if (apiAccessKeySecureParameter == null || apiAccessKeySecureParameter.getPassword() == null) {
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
