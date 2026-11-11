import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local helper: decrypts raw_payload values ("v1:..." one per line on stdin) with PAYLOAD_ENCRYPTION_KEY from .env.
 * Usage (repo root): Get-Clipboard | java scripts/DecryptPayload.java
 */
public class DecryptPayload {

    public static void main(String[] args) throws Exception {
        SecretKeySpec key = new SecretKeySpec(Base64.getDecoder().decode(readKey()), "AES");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        int n = 0;
        while ((line = in.readLine()) != null) {
            line = line.trim().replace("\"", "");
            if (line.isEmpty()) {
                continue;
            }
            n++;
            System.out.println("--- " + n);
            System.out.println(decrypt(key, line));
        }
        if (n == 0) {
            System.err.println("No input. Copy one or more raw_payload values, then: Get-Clipboard | java scripts/DecryptPayload.java");
        }
    }

    private static String decrypt(SecretKeySpec key, String stored) {
        try {
            if (!stored.startsWith("v1:")) {
                return "(not a v1 payload)";
            }
            byte[] data = Base64.getDecoder().decode(stored.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(could not decrypt: wrong key or damaged value)";
        }
    }

    private static String readKey() throws Exception {
        for (String l : Files.readAllLines(Path.of(".env"), StandardCharsets.UTF_8)) {
            if (l.startsWith("PAYLOAD_ENCRYPTION_KEY=")) {
                return l.substring("PAYLOAD_ENCRYPTION_KEY=".length()).trim();
            }
        }
        throw new IllegalStateException("PAYLOAD_ENCRYPTION_KEY not found in .env (run from the repo root)");
    }
}
