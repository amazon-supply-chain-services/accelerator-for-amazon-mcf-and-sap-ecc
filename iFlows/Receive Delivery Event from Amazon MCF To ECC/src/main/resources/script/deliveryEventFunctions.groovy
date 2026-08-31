import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.sap.gateway.ip.core.customdev.util.Message

/**
 * Extracts event details from a Fulfillment Outbound API 2020-07-01
 * FulfillmentOrderStatusNotification and sets properties for routing.
 *
 * The 2020-07-01 notification carries EventType "Order" or "Shipment".
 * These are mapped to the router's routing keys so the existing content-based
 * router branches remain unchanged:
 *
 *   "Shipment" -> "SHIPMENT_STATUS_CHANGED" (routes to the Fulfillment flow)
 *   "Order"    -> "ORDER_STATUS_CHANGED"    (routes to the Cancel flow)
 *
 * Any other EventType is passed through unmapped so the router's default/error
 * path handles it. The full event payload is passed forward unchanged.
 */
Message extractDeliveryDetails(Message message) {
    def body = message.getBody(String)
    def json = new JsonSlurper().parseText(body)

    def notification = json?.FulfillmentOrderStatusNotification
    def sourceEventType = notification?.EventType ?: "UNKNOWN"
    def orderId = notification?.SellerFulfillmentOrderId ?: ""

    // Map the 2020-07-01 EventType to the router's expected routing keys.
    def routingEventType
    switch (sourceEventType) {
        case "Shipment":
            routingEventType = "SHIPMENT_STATUS_CHANGED"
            break
        case "Order":
            routingEventType = "ORDER_STATUS_CHANGED"
            break
        default:
            routingEventType = sourceEventType
            break
    }

    // Set properties for routing
    message.setProperty("eventType", routingEventType)
    message.setProperty("sourceEventType", sourceEventType)
    message.setProperty("orderId", orderId)
    message.setHeader("SAP_ApplicationID", orderId)

    return message
}
