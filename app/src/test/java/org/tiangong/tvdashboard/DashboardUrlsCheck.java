package org.tiangong.tvdashboard;

import java.util.Objects;

/** Runs with javac/java so URL behavior needs no Android emulator or test dependency. */
public final class DashboardUrlsCheck {
    private static int checks;

    private static void equal(String expected, String actual) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        if (args.length == 2 && "root".equals(args[0])) {
            System.out.println(DashboardUrls.validRoot(args[1]));
            return;
        }
        equal("http://192.168.1.12:8787/", DashboardUrls.validRoot("http://192.168.1.12:8787"));
        equal("https://dashboard.example.org/prefix/", DashboardUrls.validRoot(" https://dashboard.example.org/prefix "));
        equal("http://[::1]:8787/base/", DashboardUrls.validRoot("http://[::1]:8787/base"));
        equal("https://example.org/a/c/", DashboardUrls.validRoot("https://example.org/a/b/../c"));
        equal("https://example.org/a%20b/", DashboardUrls.validRoot("https://example.org/a%20b"));
        equal("https://example.org/%E5%B1%95%E7%A4%BA/", DashboardUrls.validRoot("https://example.org/展示"));
        for (String invalid : new String[] {null, "", "192.168.1.12", "ftp://host/", "http:///path/",
                "http://user:password@host/", "http://host/?query=1", "http://host/#fragment",
                "http://host/?", "http://host/#", "http://host:0", "http://host:65536",
                "http://host:-1", "http://host:", "http://bad host/", "http://host/a\\b",
                "http://host/%zz", "http://host/\""}) {
            equal(null, DashboardUrls.validRoot(invalid));
        }

        // Previously saved full URLs, including a query/fragment, keep working.
        equal("http://192.0.2.20:8787/display/?view=1#gpu", DashboardUrls.validPage("http://192.0.2.20:8787/display/?view=1#gpu"));
        equal(null, DashboardUrls.validPage("http://user@host/display/"));
        String fallback = "http://192.168.1.12:8787/";
        equal("http://192.0.2.20:8787/", DashboardUrls.serverPage("http://192.0.2.20:8787/display/", "", fallback));
        equal("https://host/prefix/display/", DashboardUrls.serverPage("https://host/prefix/display/?token=1#gpu", "display/", fallback));
        equal("https://host/prefix/", DashboardUrls.serverPage("https://host/prefix/display", "", fallback));
        equal("https://host/a%20b/display/", DashboardUrls.serverPage("https://host/a%20b/display/", "display/", fallback));
        equal("http://[::1]:8787/base/", DashboardUrls.serverPage("http://[::1]:8787/base/display/", "", fallback));
        equal("https://host/", DashboardUrls.serverPage("https://host/custom-page?x=1", "", fallback));
        equal("https://host/display/", DashboardUrls.serverPage("https://host", "display/", fallback));
        equal("https://host/prefix/display/", DashboardUrls.serverPage(null, "display/", "https://host/prefix/"));
        System.out.println("Passed " + checks + " dashboard URL checks.");
    }
}
