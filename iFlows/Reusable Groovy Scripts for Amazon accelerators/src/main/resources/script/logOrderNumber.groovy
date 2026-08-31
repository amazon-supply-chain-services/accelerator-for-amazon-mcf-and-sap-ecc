import com.sap.gateway.ip.core.customdev.util.Message;

def Message processData(Message message) {
    // Retrieve the MessageLog object
    def messageLog = messageLogFactory.getMessageLog(message);

    // Check if the messageLog object is available
    if (messageLog != null) {
        // Get the value of a specific header
        def customHeaderValue = message.getHeaders().get("salesOrder");

        // Log the custom header property if its value exists
        if (customHeaderValue != null) {
            messageLog.addCustomHeaderProperty("Sales Order", customHeaderValue);
        }
    }
    return message;
}
