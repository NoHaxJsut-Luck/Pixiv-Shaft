package ceui.lisa.http;

import android.annotation.SuppressLint;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@SuppressLint("CustomX509TrustManager")
public class pixivOkHttpClient implements X509TrustManager {
    private final X509TrustManager systemTrustManager;

    public pixivOkHttpClient() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            X509TrustManager found = null;
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) {
                    found = (X509TrustManager) manager;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalStateException("System X509TrustManager is unavailable");
            }
            systemTrustManager = found;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize system trust manager", e);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        systemTrustManager.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        systemTrustManager.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return systemTrustManager.getAcceptedIssuers();
    }
}
