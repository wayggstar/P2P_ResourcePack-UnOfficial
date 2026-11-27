package dev.p2p.resourcepack;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable settings shared between the client and integrated server threads. */
public record PackSettings(boolean enabled, String url, String sha1, boolean required,
                           Mode mode, List<String> selectedIds, List<Offer> activePacks, String name) {
    public enum Mode { NONE, URL, ZIP, ACTIVE }

    private static final Pattern UNSAFE_NAME_CHARACTERS = Pattern.compile("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069§]");
    private static final int MAX_NAME_LENGTH = 120;

    public record Offer(String url, String sha1, String name) {
        public Offer(String url, String sha1) {
            this(url, sha1, "");
        }

        public Offer {
            url = normalizedUrl(url);
            sha1 = normalizedHash(sha1);
            name = normalizedName(name);
        }

        boolean verified() { return isVerified(url, sha1); }
    }

    public static final PackSettings DEFAULT = new PackSettings(false, "", "", false);

    public PackSettings(boolean enabled, String url, String sha1, boolean required) {
        this(enabled, url, sha1, required, null, List.of(), List.of());
    }

    public PackSettings(boolean enabled, String url, String sha1, boolean required,
                        Mode mode, List<String> selectedIds, List<Offer> activePacks) {
        this(enabled, url, sha1, required, mode, selectedIds, activePacks, "");
    }

    public PackSettings {
        // Old settings did not distinguish an uploaded URL from a manually entered URL.
        mode = mode == null ? (enabled ? Mode.URL : Mode.NONE) : mode;
        enabled = mode != Mode.NONE;
        selectedIds = selectedIds == null ? List.of() : List.copyOf(selectedIds);
        activePacks = activePacks == null ? List.of() : List.copyOf(activePacks);
        url = normalizedUrl(url);
        sha1 = normalizedHash(sha1);
        name = normalizedName(mode == Mode.URL ? urlFileName(url) : name);
    }

    boolean validForDelivery() {
        return switch (mode) {
            case NONE -> true;
            case URL, ZIP -> verified();
            case ACTIVE -> activePacks.stream().allMatch(Offer::verified);
        };
    }

    List<Offer> offers() {
        return switch (mode) {
            case NONE -> List.of();
            case URL, ZIP -> List.of(new Offer(url, sha1, name));
            case ACTIVE -> activePacks;
        };
    }

    PackSettings withActivePacks(List<Offer> packs) {
        return new PackSettings(enabled, url, sha1, required, mode, selectedIds, packs, name);
    }

    public boolean verified() { return isVerified(url, sha1); }

    private static String normalizedUrl(String url) {
        return url == null ? "" : url.trim();
    }

    private static String urlFileName(String url) {
        try {
            String path = checkUrl(url).getPath();
            String file = path.substring(path.lastIndexOf('/') + 1);
            return file.toLowerCase(Locale.ROOT).endsWith(".zip") ? file : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String normalizedName(String value) {
        if (value == null || value.isBlank()) return "";
        String name = UNSAFE_NAME_CHARACTERS.matcher(value).replaceAll(" ").strip();
        if (name.codePointCount(0, name.length()) <= MAX_NAME_LENGTH) return name;
        return name.substring(0, name.offsetByCodePoints(0, MAX_NAME_LENGTH - 1)) + "…";
    }

    private static String normalizedHash(String sha1) {
        return sha1 == null ? "" : sha1.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isVerified(String url, String sha1) {
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
