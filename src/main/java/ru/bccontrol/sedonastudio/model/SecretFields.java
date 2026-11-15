package ru.bccontrol.sedonastudio.model;
/** Names excluded from document export and editing independently of the transport. */
public final class SecretFields {
  private SecretFields() {}

  public static boolean matches(String name) {
    String n = name.toLowerCase(java.util.Locale.ROOT);
    return n.contains("password")
        || n.contains("credential")
        || n.equals("cred")
        || n.contains("secret")
        || n.equals("pwd")
        || n.equals("pass")
        || n.equals("pin")
        || n.contains("token")
        || n.endsWith("privatekey")
        || n.equals("apikey");
  }
}
