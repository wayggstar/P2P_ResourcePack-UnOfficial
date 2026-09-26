package dev.p2p.resourcepack;

import java.net.URI;

/** Immutable settings shared between the client and integrated server threads. */
public record PackSettings(boolean enabled, String url, String sha1, boolean required) {
    public static final PackSettings DEFAULT = new PackSettings(false, "", "", false);

    public PackSettings {
        url = url == null ? "" : url.trim();
        sha1 = sha1 == null ? "" : sha1.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean verified() {
        try {
            checkUrl(url);
            return sha1.matches("[0-9a-f]{40}");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static URI checkUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || value.length() > 8192) {
                throw new IllegalArgumentException("Use a direct HTTP(S) download URL without credentials or fragments.");
            }
            return uri;
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("A download URL is required.", e);
        }
    }
}
