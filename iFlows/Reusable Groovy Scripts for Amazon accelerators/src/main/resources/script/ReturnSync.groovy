import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import groovy.json.JsonOutput
import groovy.util.XmlParser
import groovy.xml.XmlUtil


def Message generateGraphQLReturnOrderRequest(Message message) {

    // --- Step 1: Retrieve message properties ---
    def props = message.getProperties()
    def amazonOrderId = props.get("amazonOrderId")
    def customerReturnId = props.get("customerReturnId")
    def overallSDProcessStatus = props.get("overallSDProcessStatus")

    // Configurable Production Plant filter
    def targetPlant = props.get("plant")  // e.g., 1710

    if (!targetPlant) {
        throw new IllegalStateException("Missing targetProductionPlant property.")
    }

    
 

    // --- Step 3: Parse the incoming XML payload ---
    def body = message.getBody(String)
    if (!body) {
        throw new IllegalStateException("Empty message body")
    }

    def xml = new XmlParser(false, false).parseText(body)
    def graphQLQuery = ""

    // --- Step 4: Extract ONLY items matching ProductionPlant ---
    def lineItemList = []

    xml.CustomerReturn.Item.each { item ->

        // Filter by ProductionPlant from CPI
        def productionPlant = item.ProductionPlant?.text()?.trim()

        if (productionPlant != targetPlant) {
            // Skip this item
            return
        }

        def refItemId = item.ReferenceDocumentItem[0]?.ReferenceSDDocumentItemID[0]?.text()
        def reqQty = item.RequestedQuantityInBaseUnit[0]?.text()

        if (refItemId && reqQty) {

            // Build block
            def lineItem = """{
                returnFor: {
                    orderLineItemAmounts: [
                        {
                            amount: { value: ${reqQty} },
                            lineItemId: { alias: { aliasType: "EXTERNAL_ID", aliasId: "${refItemId}" } }
                        }
                    ]
                }
            }"""

            lineItemList << lineItem
        }
    }

    // If no matching items exist → Do not create a GraphQL return
    if (lineItemList.isEmpty()) {
        throw new IllegalStateException("No line items found for ProductionPlant ${targetPlant}")
    }

    def returnLineItemsBlock = lineItemList.join(",\n")

    // --- Step 5: Build GraphQL mutation ---
    graphQLQuery = """mutation updateOrder {
        updateOrder(
            orderIdentifier: { orderId: "${amazonOrderId}" },
            input: {
                returns: {
                    details: [
                        {
                            aliases: [
                                { aliasType: "EXTERNAL-RETURN-ID", aliasId: "${customerReturnId}" }
                            ],
                            state: ${overallSDProcessStatus},
                            returnLineItems: [ ${returnLineItemsBlock} ]
                        }
                    ]
                }
            }
        ) {
            order {
                id
                returns {
                    details {
                        id
                        createdAt
                        updatedAt
                        aliases { aliasType aliasId }
                        state
                        returnLineItems {
                            id
                            returnFor {
                                orderLineItemAmounts {
                                    amount { value }
                                    lineItem { id amount { value } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }"""

    // --- Step 6: Wrap into JSON ---
    def graphqlJson = JsonOutput.toJson([query: graphQLQuery])
    message.setBody(graphqlJson)
    message.setHeader("Content-Type", "application/json")

    // --- Step 7: Retrieve credentials from Secure Store ---
    String apiTargetIDSecureParameterName = props.get("apiTargetIdSecureParameter")
    String apiAccessKeySecureParameterName = props.get("apiAccessKeySecureParameter")

    def secureStoreService = ITApiFactory.getApi(SecureStoreService.class, null)
    def apiTargetIDSecureParameter = secureStoreService.getUserCredential(apiTargetIDSecureParameterName)
    def apiAccessKeySecureParameter = secureStoreService.getUserCredential(apiAccessKeySecureParameterName)

    if (!apiTargetIDSecureParameter?.getPassword())
        throw new IllegalStateException("Missing Secure Parameter: ${apiTargetIDSecureParameterName}")

    if (!apiAccessKeySecureParameter?.getPassword())
        throw new IllegalStateException("Missing Secure Parameter: ${apiAccessKeySecureParameterName}")

    String apiTargetID = new String(apiTargetIDSecureParameter.getPassword())
    String apiAccessKey = new String(apiAccessKeySecureParameter.getPassword())

    // --- Step 8: Set HTTP headers ---
    message.setHeader("x-api-access-key", apiAccessKey)
    message.setHeader("X-Omni-TargetId", apiTargetID)
    message.setHeader("x-api-version", props.get("apiVersion"))

    // --- Step 9: Log mapped output ---
    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addAttachmentAsString("After Mapping Message", graphQLQuery, "text/plain")
    }

    return message
}

Message appendPlant(Message message) {

    // Read inputs: incomingXML (from property) and originalXML (prefer body, fallback to property)
    def incomingXML = message.getProperty("incomingXML")
    def originalXML = message.getBody(String) ?: message.getProperty("originalXML")

    if (!incomingXML || !originalXML) {
        throw new IllegalStateException("incomingXML or originalXML missing.")
    }

    // Use XmlParser to get mutable Node objects. Disable namespace awareness for simpler node names.
    def parser = new XmlParser(false, false)
    def incomingRoot = parser.parseText(incomingXML)
    def originalRoot = parser.parseText(originalXML)

    // --- Build map: SalesOrderItem -> ProductionPlant ---
    def itemToPlantMap = [:]
    // Navigate according to incoming payload structure
    incomingRoot.'A_SalesOrderType'.'to_Item'.'A_SalesOrderItemType'.each { it ->
        def soItem = it.'SalesOrderItem'?.text()?.trim()
        def plant  = it.'ProductionPlant'?.text()?.trim()
        if (soItem && plant) {
            itemToPlantMap[soItem] = plant
        }
    }

    if (itemToPlantMap.isEmpty()) {
        // No production plants found — either proceed silently or throw. We'll proceed (no-op).
        // throw new IllegalStateException("No ProductionPlant values found in incomingXML.")
    }

    // --- For each Item node in original XML, match and append ProductionPlant if needed ---
    originalRoot.'CustomerReturn'.'Item'.each { itemNode ->
        def itemId = itemNode.'CustomerReturnItemID'?.text()?.trim()
        if (itemId && itemToPlantMap.containsKey(itemId)) {
            def plantValue = itemToPlantMap[itemId]
            // Add only if not present
            if (!itemNode.'ProductionPlant' || itemNode.'ProductionPlant'.size() == 0) {
                itemNode.appendNode('ProductionPlant', plantValue)
            }
        }
    }

    // Serialize modified XML back to string
    def finalPayload = XmlUtil.serialize(originalRoot)

    message.setBody(finalPayload)
    return message
}

