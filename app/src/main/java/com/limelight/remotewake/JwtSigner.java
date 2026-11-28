package com.limelight.remotewake;

import com.google.gson.JsonObject;

import org.bouncycastle.util.encoders.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Builds the signed JWT assertion used to obtain an OAuth2 access token
 * for a Google service account (RS256).
 */
final class JwtSigner {
    static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final long LIFETIME_SECONDS = 3600;

    private JwtSigner() {}

    static String createJwt(ServiceAccount account, long nowSeconds) throws GeneralSecurityException {
        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");

        JsonObject claims = new JsonObject();
        claims.addProperty("iss", account.clientEmail);
        claims.addProperty("scope", FCM_SCOPE);
        claims.addProperty("aud", account.tokenUri);
        claims.addProperty("iat", nowSeconds);
        claims.addProperty("exp", nowSeconds + LIFETIME_SECONDS);

        String signingInput = base64Url(header.toString().getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(claims.toString().getBytes(StandardCharsets.UTF_8));

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(parsePrivateKey(account.privateKeyPem));
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));

        return signingInput + "." + base64Url(signature.sign());
    }

    static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.decode(body);
        } catch (RuntimeException e) {
            throw new GeneralSecurityException("private_key is not valid base64", e);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    static String base64Url(byte[] data) {
        String s = Base64.toBase64String(data);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') {
            end--;
        }
        return s.substring(0, end).replace('+', '-').replace('/', '_');
    }
}
