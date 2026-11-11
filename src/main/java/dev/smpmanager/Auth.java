package dev.smpmanager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

final class Auth {
    static String hash(String key,String code) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update("POST\n/rpc\ntoken\nhash\n".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
