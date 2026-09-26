import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Loopback-only fixture. Responses and diagnostics contain no request data. */
public final class LocalTlsFixture {
    public static void main(String[] args) throws Exception {
        char[] password = args[1].toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
            store.load(input, password);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);

        InetAddress loopback = InetAddress.getLoopbackAddress();
        HttpServer clear = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        HttpsServer secure = HttpsServer.create(new InetSocketAddress(loopback, 0), 0);
        secure.setHttpsConfigurator(new HttpsConfigurator(tls));
        clear.createContext("/", LocalTlsFixture::reply);
        secure.createContext("/", LocalTlsFixture::reply);
        clear.start();
        secure.start();
        System.out.println("READY " + clear.getAddress().getPort() + " " + secure.getAddress().getPort());
        System.out.flush();
        System.in.read();
        clear.stop(0);
        secure.stop(0);
    }

    private static void reply(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(204, -1);
        } catch (Exception ignored) {
            // A rejected TLS handshake never reaches this handler.
        } finally {
            exchange.close();
        }
    }
}
