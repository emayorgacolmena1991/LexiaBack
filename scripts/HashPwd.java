import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
public class HashPwd {
  public static void main(String[] args) {
    var e = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    System.out.println(e.encode(args[0]));
  }
}
