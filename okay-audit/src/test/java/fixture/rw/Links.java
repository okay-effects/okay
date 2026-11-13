package fixture.rw;

import java.nio.charset.StandardCharsets;

/** java.net's pure values: string codecs and a URI; `toURL` and a socket stay reaches */
public final class Links {
  public static String link(String q) {
    String e = java.net.URLEncoder.encode(q, StandardCharsets.UTF_8);
    String d = java.net.URLDecoder.decode(e, StandardCharsets.UTF_8);
    return java.net.URI.create("https://example.org/").resolve(d).getHost();
  }
  public static Object url(String s) throws Exception { return java.net.URI.create(s).toURL(); }
}
