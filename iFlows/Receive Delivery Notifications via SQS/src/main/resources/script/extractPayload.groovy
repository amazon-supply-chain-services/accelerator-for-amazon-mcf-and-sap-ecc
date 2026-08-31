import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.sap.gateway.ip.core.customdev.util.Message

/**
 * Extracts the inner notification Payload from the SQS message envelope and
 * sets it as the outgoing message body, so downstream flows receive only the
 * FulfillmentOrderStatusNotification content.
 *
 * Amazon delivers each SQS message as a notification envelope, e.g.:
 *   {
 *     "NotificationVersion": "1.0",
 *     "NotificationType": "FULFILLMENT_ORDER_STATUS",
 *     "PayloadVersion": "1.0",
 *     "EventTime": "...",
 *     "Payload": { "FulfillmentOrderStatusNotification": { ... } },
 *     "NotificationMetadata": { ... }
 *   }
 *
 * This script forwards only the Payload object:
 *   { "FulfillmentOrderStatusNotification": { ... } }
 *
 * which is the shape the Event Router (extractDeliveryDetails) expects.
 *
 * Behavior:
 *  - If the body has a "Payload" element, the Payload object is forwarded.
 *  - If the body is already the unwrapped payload (has
 *    "FulfillmentOrderStatusNotification" at the top level), it is passed
 *    through unchanged.
 *  - The NotificationType is surfaced as a message property/header for
 *    logging/traceability when present.
 */
Message extractPayload(Message message) {
    def body = message.getBody(String) ?: ""

    // Nothing to do for an empty body.
    if (body.trim().isEmpty()) {
        return message
    }

    def json = new JsonSlurper().parseText(body)

    if (json instanceof Map && json.containsKey("Payload") && json.Payload != null) {
        // Surface notification metadata for logging before unwrapping.
        if (json.NotificationType != null) {
            message.setProperty("NotificationType", json.NotificationType.toString())
            message.setHeader("NotificationType", json.NotificationType.toString())
        }
        if (json.NotificationMetadata instanceof Map && json.NotificationMetadata.NotificationId != null) {
            message.setProperty("NotificationId", json.NotificationMetadata.NotificationId.toString())
        }

        message.setBody(JsonOutput.toJson(json.Payload))
    }
    // else: body is already the unwrapped payload (or an unrecognized shape) —
    // pass it through unchanged so the router can handle it.

    return message
}
