package org.tiangong.tvdashboard;

import java.net.URI;
import java.net.URISyntaxException;

/** URL validation and dashboard-relative navigation, without Android dependencies. */
final class DashboardUrls {
    private DashboardUrls() {}

    static String validPage(String value) {
        if (value == null) return null;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) {
                return null;
            }
            return uri.toASCIIString();
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    static String validRoot(String value) {
        String page = validPage(value);
        if (page == null) return null;
        URI uri = URI.create(page);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
        String root = uri.normalize().toASCIIString();
        return root.endsWith("/") ? root : root + "/";
    }

    /**
     * Keep previously saved full startup URLs. For /prefix/display/ recover the
     * server's /prefix/ root; arbitrary saved pages retain the historic origin root.
     */
    static String serverPage(String startupUrl, String relativePage, String fallbackRoot) {
        String startup = validPage(startupUrl);
        String root = validRoot(fallbackRoot);
        if (root == null) throw new IllegalArgumentException("Invalid fallback dashboard root");
        if (startup != null) {
            URI address = URI.create(startup);
            String path = address.getRawPath();
            if (path.endsWith("/display/")) {
                path = path.substring(0, path.length() - "display/".length());
            } else if (path.endsWith("/display")) {
                path = path.substring(0, path.length() - "display".length());
            } else {
                path = "/";
            }
            root = address.getScheme() + "://" + address.getRawAuthority() + path;
        }
        if (!("".equals(relativePage) || "display/".equals(relativePage))) {
            throw new IllegalArgumentException("Unsupported dashboard-relative page");
        }
        return URI.create(root).resolve(relativePage).toASCIIString();
    }
}
