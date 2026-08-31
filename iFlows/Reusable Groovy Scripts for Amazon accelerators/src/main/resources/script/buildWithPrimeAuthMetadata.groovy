import com.sap.gateway.ip.core.customdev.util.Message;

def Message processData(Message message)
{
    def map = message.getProperties();

    // Read credentials from properties instead of Secure Store
    def targetID = map.get("Amazon_BwP_GraphQL_API_Target_ID");
    def accessKey = map.get("Amazon_BwP_GraphQL_API_Access_Key");

    if (targetID == null || targetID.trim().isEmpty()) {
        throw new IllegalStateException("Property Amazon_BwP_GraphQL_API_Target_ID is missing or empty");
    }
    if (accessKey == null || accessKey.trim().isEmpty()) {
        throw new IllegalStateException("Property Amazon_BwP_GraphQL_API_Access_Key is missing or empty");
    }

    // Set HTTP headers
    message.setHeader("x-api-access-key", accessKey);
    message.setHeader("X-Omni-TargetId", targetID);
    message.setHeader("x-api-version", "2024-11-01");
    message.setHeader("Accept", "application/json");
    message.setHeader("Content-Type", "application/json");

    return message;
}
