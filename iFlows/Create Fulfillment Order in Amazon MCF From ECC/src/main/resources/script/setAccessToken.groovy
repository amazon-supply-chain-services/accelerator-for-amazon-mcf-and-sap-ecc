import com.sap.gateway.ip.core.customdev.util.Message;
import groovy.json.JsonSlurper;

def Message processData(Message message)
{
    def map = message.getProperties();
    String authorizationHeaderKey = map.get("P_AUTH_HEADER_KEY")

    // Read body as String (not Reader) to avoid stream consumption issues
    def body = message.getBody(java.lang.String) as String;
    def json = new JsonSlurper().parseText(body);

    def accessToken = json.access_token;

    if (accessToken == null || accessToken.toString().trim().isEmpty()) {
        throw new IllegalStateException("No access token returned for Selling Partner API authentication");
    }

    message.setHeader(authorizationHeaderKey, accessToken.toString().trim());

    // Restore SP-API request body saved before auth flow
    def spApiRequestBody = map.get("spApiRequestBody")
    if (spApiRequestBody != null && !spApiRequestBody.toString().trim().isEmpty()) {
        message.setBody(spApiRequestBody)
        message.setHeader("Content-Type", "application/json")
    } else {
        message.setBody("")
    }

    return message;
}
