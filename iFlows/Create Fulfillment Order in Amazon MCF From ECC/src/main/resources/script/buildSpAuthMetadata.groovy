import com.sap.gateway.ip.core.customdev.util.Message;
import com.sap.it.api.ITApiFactory;
import com.sap.it.api.securestore.SecureStoreService;
import java.nio.charset.StandardCharsets

def Message processData(Message message)
{
    def map = message.getProperties();
    String sellingPartnerAPICredentialsName = map.get("P_SELLING_PARTNER_API_SECURITY_MATERIAL");
    String sellingPartnerAPISecureParameterName = map.get("P_SELLING_PARTNER_API_SECURE_PARAMETER");
    String grantType = map.get("P_GRANT_TYPE");

    def secureStoreService = ITApiFactory.getApi(SecureStoreService.class, null);
    def credentials = secureStoreService.getUserCredential(sellingPartnerAPICredentialsName);
    def refreshTokenSecureParameter = secureStoreService.getUserCredential(sellingPartnerAPISecureParameterName);

    if (credentials == null || credentials.getUsername() == null || credentials.getPassword() == null)
    {
        throw new IllegalStateException("No credentials found with name ${sellingPartnerAPICredentialName} in Secure Store");
    }

    if (refreshTokenSecureParameter == null || refreshTokenSecureParameter.getPassword() == null)
    {
        throw new IllegalStateException("No secure parameter found with name ${sellingPartnerAPISecureParameterName} in Secure Store");
    }

    String clientId = credentials.getUsername();
    String clientSecret = new String(credentials.getPassword());
    String refreshToken = new String(refreshTokenSecureParameter.getPassword());

    if (clientId == null || clientSecret == null || grantType == null || refreshToken == null) {
        throw new IllegalStateException("Missing required properties: client_id, client_secret, grant_type, or refresh_token");
    }

    //function to URL encode parameters
    def encodeParameter = { String value -> 
        return java.net.URLEncoder.encode(value, "UTF-8");
    }

    def formBody = "client_id=${encodeParameter(clientId)}&client_secret=${encodeParameter(clientSecret)}&grant_type=${encodeParameter(grantType)}&refresh_token=${encodeParameter(refreshToken)}";
    message.setBody(formBody.getBytes(StandardCharsets.UTF_8));
    message.setHeader("Content-Type", "application/x-www-form-urlencoded"); 
    message.setHeader("Accept", "application/json");

    return message;
}
