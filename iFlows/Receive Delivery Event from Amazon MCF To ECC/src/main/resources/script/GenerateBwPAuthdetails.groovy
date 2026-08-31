
import com.sap.gateway.ip.core.customdev.util.Message;
import com.sap.it.api.ITApiFactory;
import com.sap.it.api.securestore.SecureStoreService;
import com.sap.it.api.securestore.exception.SecureStoreException;

def Message processData(Message message) {
    def map = message.getProperties();

    // Read the secure parameter targetID from properties
    def secureParameterAliasTarget_ID = map.get("amazon_BwP_GraphQL_API_Target_ID");
    
    if (secureParameterAliasTarget_ID == null || secureParameterAliasTarget_ID.trim().isEmpty()) {
        throw new IllegalStateException("Property amazon_BwP_GraphQL_API_Target_ID is missing or empty");
    }



    // Read the secure parameter alias from properties
    def secureParameterAliasAccess_Key = map.get("amazon_BwP_GraphQL_API_Access_Key");
    
    if (secureParameterAliasAccess_Key == null || secureParameterAliasAccess_Key.trim().isEmpty()) {
        throw new IllegalStateException("Property amazon_BwP_GraphQL_API_Access_Key is missing or empty");
    }

    // Get secure store service
    def secureStorageService = ITApiFactory.getService(SecureStoreService.class, null);
     
try {
    // Fetch credentials from secure parameter using the alias for target ID
    def secureParameter = secureStorageService.getUserCredential(secureParameterAliasTarget_ID)
    if (secureParameter == null) {
        throw new IllegalStateException("No credential found for alias '" + secureParameterAliasTarget_ID + "'")
    }

    // Store the target ID password
    def targetID = new String(secureParameter.getPassword())

    // Fetch credentials for access key, assign to same variable
    secureParameter = secureStorageService.getUserCredential(secureParameterAliasAccess_Key)
    if (secureParameter == null) {
        throw new IllegalStateException("No credential found for alias '" + secureParameterAliasAccess_Key + "'")
    }

    // Store the access key password
    def accessKey = new String(secureParameter.getPassword())

    if (targetID == null || targetID.trim().isEmpty()) {
        throw new IllegalStateException("Target ID is missing or empty in secure parameter")
    }

    if (accessKey == null || accessKey.trim().isEmpty()) {
        throw new IllegalStateException("Access Key is missing or empty in secure parameter")
    }

    // Set headers
    message.setHeader("x-api-access-key", accessKey)
    message.setHeader("X-Omni-TargetId", targetID)
    message.setHeader("x-api-version", map.get("apiVersion"))
    message.setHeader("Accept", "application/json")
    message.setHeader("Content-Type", "application/json")

} catch (SecureStoreException e) {
    throw new SecureStoreException("Failed to retrieve secure parameter: " + e.getMessage())
} catch (Exception e) {
    throw new IllegalStateException("Error accessing secure parameter: " + e.getMessage())
}


    return message;
}

