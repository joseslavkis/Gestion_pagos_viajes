package com.agencia.pagos.config.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.storage.receipts")
public class PaymentAttachmentStorageProperties {

    private Provider provider = Provider.INLINE;
    private final FilesystemProperties filesystem = new FilesystemProperties();
    private final CleanupProperties cleanup = new CleanupProperties();

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public FilesystemProperties getFilesystem() {
        return filesystem;
    }

    public CleanupProperties getCleanup() {
        return cleanup;
    }

    public enum Provider {
        INLINE,
        FILESYSTEM
    }

    public static class FilesystemProperties {
        private String basePath = "/tmp/gestion-pagos-viajes";
        private String publicBaseUrl = "http://localhost:8080";
        private long urlExpirationMinutes = 15;
        private String pathPrefix = "receipts";

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public long getUrlExpirationMinutes() {
            return urlExpirationMinutes;
        }

        public void setUrlExpirationMinutes(long urlExpirationMinutes) {
            this.urlExpirationMinutes = urlExpirationMinutes;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }
    }

    public static class CleanupProperties {
        private boolean enabled = true;
        private long retentionDays = 365;
        private int batchSize = 100;
        private String cron = "0 0 3 * * *";
        private String zone = "America/Argentina/Buenos_Aires";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(long retentionDays) {
            this.retentionDays = retentionDays;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }
}
