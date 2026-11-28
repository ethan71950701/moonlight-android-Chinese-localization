package com.limelight.remotewake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

public class RemoteWakeTest {
    private static String pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private static String accountJson(String privateKeyPem) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "service_account");
        o.addProperty("project_id", "demo-project");
        o.addProperty("client_email", "wol@demo-project.iam.gserviceaccount.com");
        o.addProperty("private_key", privateKeyPem);
        return o.toString();
    }

    @Test
    public void normalizeMacAcceptsCommonFormats() {
        assertEquals("aa:bb:cc:dd:ee:ff", RemoteWakeConfig.normalizeMac("AA:BB:CC:DD:EE:FF"));
        assertEquals("aa:bb:cc:dd:ee:ff", RemoteWakeConfig.normalizeMac(" aa-bb-cc-dd-ee-ff "));
        assertEquals("aa:bb:cc:dd:ee:ff", RemoteWakeConfig.normalizeMac("aabbccddeeff"));
    }

    @Test
    public void normalizeMacRejectsInvalidInput() {
        assertNull(RemoteWakeConfig.normalizeMac(null));
        assertNull(RemoteWakeConfig.normalizeMac(""));
        assertNull(RemoteWakeConfig.normalizeMac("AA:BB:CC:DD:EE"));
        assertNull(RemoteWakeConfig.normalizeMac("GG:BB:CC:DD:EE:FF"));
        assertNull(RemoteWakeConfig.normalizeMac("AA:BB:CC:DD:EE:FF:00"));
    }

    @Test
    public void serviceAccountParsesRequiredFields() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        ServiceAccount account = ServiceAccount.parse(accountJson(pem(gen.generateKeyPair())));

        assertEquals("demo-project", account.projectId);
        assertEquals("wol@demo-project.iam.gserviceaccount.com", account.clientEmail);
        assertEquals(ServiceAccount.DEFAULT_TOKEN_URI, account.tokenUri);
    }

    @Test
    public void serviceAccountRejectsBadInput() {
        String[] bad = {
                "", "not json", "[]",
                "{\"type\":\"authorized_user\"}",
                "{\"type\":\"service_account\",\"project_id\":\"p\"}",
                "{\"project_id\":\"p\",\"client_email\":\"e\",\"private_key\":\"abc\"}",
        };
        for (String json : bad) {
            try {
                ServiceAccount.parse(json);
                fail("should have been rejected: " + json);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void jwtIsSignedWithAccountKeyAndHasExpectedClaims() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        ServiceAccount account = ServiceAccount.parse(accountJson(pem(pair)));

        String jwt = JwtSigner.createJwt(account, 1_700_000_000L);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        assertTrue("must be base64url without padding", !jwt.contains("=") && !jwt.contains("+") && !jwt.contains("/"));

        JsonObject header = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("RS256", header.get("alg").getAsString());

        JsonObject claims = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(account.clientEmail, claims.get("iss").getAsString());
        assertEquals(JwtSigner.FCM_SCOPE, claims.get("scope").getAsString());
        assertEquals(ServiceAccount.DEFAULT_TOKEN_URI, claims.get("aud").getAsString());
        assertEquals(1_700_000_000L, claims.get("iat").getAsLong());
        assertEquals(1_700_003_600L, claims.get("exp").getAsLong());

        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(pair.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test
    public void errorSummaryReadsGoogleErrorBodies() {
        assertEquals("UNREGISTERED Requested entity was not found.",
                FcmSender.errorSummary("{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\",\"status\":\"UNREGISTERED\"}}"));
        assertEquals("invalid_grant: Invalid JWT",
                FcmSender.errorSummary("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT\"}"));
        assertEquals("plain text", FcmSender.errorSummary("plain text"));
    }

    @Test
    public void parseMacListNormalizesDeduplicatesAndSkipsInvalid() {
        java.util.List<String> macs = RemoteWakeConfig.parseMacList(
                "AA:BB:CC:DD:EE:FF\n11-22-33-44-55-66, aabbccddeeff;bogus");
        assertEquals(java.util.Arrays.asList("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"), macs);
        assertTrue(RemoteWakeConfig.parseMacList(null).isEmpty());
        assertTrue(RemoteWakeConfig.parseMacList("  \n ").isEmpty());
    }

    @Test
    public void findInvalidMacReportsFirstBadEntry() {
        assertNull(RemoteWakeConfig.findInvalidMac("AA:BB:CC:DD:EE:FF\n11:22:33:44:55:66"));
        assertNull(RemoteWakeConfig.findInvalidMac(""));
        assertEquals("zz", RemoteWakeConfig.findInvalidMac("AA:BB:CC:DD:EE:FF\nzz"));
    }

    @Test
    public void requestJsonIsHighPriorityDataMessage() {
        JsonObject root = JsonParser.parseString(
                FcmSender.buildRequestJson("TOKEN123", "aa:bb:cc:dd:ee:ff")).getAsJsonObject();
        JsonObject message = root.getAsJsonObject("message");
        assertEquals("TOKEN123", message.get("token").getAsString());
        assertEquals("wake", message.getAsJsonObject("data").get("action").getAsString());
        assertEquals("aa:bb:cc:dd:ee:ff", message.getAsJsonObject("data").get("mac").getAsString());
        assertEquals("HIGH", message.getAsJsonObject("android").get("priority").getAsString());
        assertEquals("60s", message.getAsJsonObject("android").get("ttl").getAsString());
        assertTrue("no notification block: data-only message", !message.has("notification"));
    }

    @Test
    public void requestJsonOmitsMacWhenNotGiven() {
        JsonObject data = JsonParser.parseString(FcmSender.buildRequestJson("T", null))
                .getAsJsonObject().getAsJsonObject("message").getAsJsonObject("data");
        assertTrue(!data.has("mac"));
        assertEquals("wake", data.get("action").getAsString());
    }
}
