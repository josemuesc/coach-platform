import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Prints the BCrypt hash (same encoder and strength as the application) of the password read from STANDARD INPUT, so the password
 * never appears in the process arguments or in the shell history. With the argument "check <hash>" it reads a password from
 * standard input and prints MATCH / NO MATCH.
 */
public class BcryptHash {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String password = in.readLine();
        if (password == null || password.isEmpty()) {
            System.err.println("empty password");
            System.exit(2);
        }
        if (password.getBytes("UTF-8").length > 72) {
            System.err.println("a password over 72 bytes is not accepted by the application");
            System.exit(2);
        }
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        if (args.length == 2 && args[0].equals("check")) {
            System.out.println(encoder.matches(password, args[1]) ? "MATCH" : "NO MATCH");
        } else {
            System.out.println(encoder.encode(password));
        }
    }
}
