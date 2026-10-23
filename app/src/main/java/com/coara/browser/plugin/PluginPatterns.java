package com.coara.browser.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class PluginPatterns {
    public static final List<String> DEFAULT_MATCHES;
    public static final String NO_MATCH = "/(?!)/";
    public static final List<String> NO_MATCH_LIST = java.util.Collections.singletonList(NO_MATCH);
    private static final String NO_MATCH_ORIGIN = "https://no-match.invalid";

    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_PATTERN_LIST = 128;
    private static final int MAX_LIST_CHARS = 65536;
    private static final String SPECIALS = "\\.[]{}()+^$|";

    static {
        List<String> d = new ArrayList<>();
        d.add("http://*");
        d.add("https://*");
        DEFAULT_MATCHES = java.util.Collections.unmodifiableList(d);
    }

    private PluginPatterns() {
    }

    public static List<String> parseList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        int end = Math.min(raw.length(), MAX_LIST_CHARS);
        int start = 0;
        while (start < end && out.size() < MAX_PATTERN_LIST) {
            while (start < end && (Character.isWhitespace(raw.charAt(start)) || raw.charAt(start) == ';')) {
                start++;
            }
            if (start >= end) {
                break;
            }
            int stop = start;
            while (stop < end && !Character.isWhitespace(raw.charAt(stop)) && raw.charAt(stop) != ';') {
                stop++;
            }
            addListToken(out, raw.substring(start, stop));
            start = stop;
        }
        return out;
    }

    private static void addListToken(List<String> out, String token) {
        String value = token.trim();
        if (value.isEmpty() || out.size() >= MAX_PATTERN_LIST) {
            return;
        }
        if (value.length() > 2 && value.startsWith("/") && value.endsWith("/")) {
            if (value.length() <= 514) out.add(value);
            return;
        }
        int start = 0;
        while (start <= value.length() && out.size() < MAX_PATTERN_LIST) {
            int comma = value.indexOf(',', start);
            int end = comma < 0 ? value.length() : comma;
            String part = value.substring(start, end).trim();
            if (!part.isEmpty() && part.length() <= 2048) {
                out.add(part);
            }
            if (comma < 0) break;
            start = comma + 1;
        }
    }

    public static String toRegex(String pattern) {
        if (pattern == null) {
            return null;
        }
        String p = pattern.trim();
        if (p.isEmpty() || p.length() > 2048) {
            return null;
        }
        if (p.equals("*")) {
            return "^.*$";
        }
        if (p.equalsIgnoreCase("<all_urls>")) {
            return "^(https?|file)://.*$";
        }
        if (p.length() > 2 && p.startsWith("/") && p.endsWith("/")) {
            String inner = p.substring(1, p.length() - 1);
            if (inner.length() > 512 || isRiskyRegex(inner)) {
                return null;
            }
            try {
                Pattern.compile(inner);
            } catch (Exception e) {
                return null;
            }
            return inner;
        }
        StringBuilder sb = new StringBuilder(p.length() + 8);
        sb.append('^');
        int wildcards = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') {
                wildcards++;
                if (wildcards > 32) {
                    return null;
                }
                if (i == 0 || p.charAt(i - 1) != '*') {
                    sb.append(".*");
                }
            } else if (c == '?') {
                sb.append('.');
            } else if (SPECIALS.indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append('$');
        String regex = sb.toString();
        if (isRiskyRegex(regex)) {
            return null;
        }
        try {
            Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
        return regex;
    }

    private static boolean isRiskyRegex(String regex) {
        if (regex == null || regex.length() > 4096) {
            return true;
        }
        if (Pattern.compile("\\\\[1-9]").matcher(regex).find()) {
            return true;
        }
        List<boolean[]> groups = new ArrayList<>();
        groups.add(new boolean[2]);
        boolean escaped = false;
        boolean inClass = false;
        int quantifiers = 0;
        int unboundedQuantifiers = 0;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '[') {
                inClass = true;
                continue;
            }
            if (c == ']') {
                inClass = false;
                continue;
            }
            if (inClass) {
                continue;
            }
            if (c == '(') {
                if (groups.size() > 64) {
                    return true;
                }
                groups.add(new boolean[2]);
                continue;
            }
            if (c == '|') {
                groups.get(groups.size() - 1)[1] = true;
                continue;
            }
            if (c == ')') {
                if (groups.size() <= 1) {
                    continue;
                }
                boolean[] group = groups.remove(groups.size() - 1);
                int j = i + 1;
                boolean repeated = j < regex.length() && (regex.charAt(j) == '*' || regex.charAt(j) == '+');
                if (!repeated && j < regex.length() && regex.charAt(j) == '{') {
                    int close = regex.indexOf('}', j + 1);
                    repeated = close > j + 1 && Character.isDigit(regex.charAt(j + 1));
                }
                if (repeated && (group[0] || group[1])) {
                    return true;
                }
                if (repeated) {
                    groups.get(groups.size() - 1)[0] = true;
                }
                continue;
            }
            boolean unbounded = c == '*' || c == '+';
            boolean quantifier = unbounded || c == '?';
            if (c == '{') {
                int close = regex.indexOf('}', i + 1);
                quantifier = close > i + 1 && Character.isDigit(regex.charAt(i + 1));
            }
            if (quantifier && !(c == '?' && i > 0 && regex.charAt(i - 1) == '(')) {
                quantifiers++;
                if (unbounded) unboundedQuantifiers++;
                if (quantifiers > 24 || unboundedQuantifiers > 3) {
                    return true;
                }
                groups.get(groups.size() - 1)[0] = true;
            }
        }
        return false;
    }

    public static Set<String> toAllowedOriginRules(List<String> patterns) {
        Set<String> out = new LinkedHashSet<>();
        List<String> effective = effectiveMatches(patterns);
        boolean fallback = false;
        for (String raw : effective) {
            if (raw == null) {
                fallback = true;
                continue;
            }
            String p = raw.trim();
            if (p.equals(NO_MATCH)) {
                out.add(NO_MATCH_ORIGIN);
                continue;
            }
            if (p.isEmpty() || p.equals("*") || p.equalsIgnoreCase("<all_urls>")) {
                fallback = true;
                continue;
            }
            if (p.length() > 2 && p.startsWith("/") && p.endsWith("/")) {
                fallback = true;
                continue;
            }
            int sep = p.indexOf("://");
            if (sep <= 0) {
                fallback = true;
                continue;
            }
            String scheme = p.substring(0, sep).toLowerCase(java.util.Locale.US);
            String rest = p.substring(sep + 3);
            int slash = rest.indexOf('/');
            String host = slash < 0 ? rest : rest.substring(0, slash);
            if (scheme.equals("*")) {
                addOrigin(out, "http", host);
                addOrigin(out, "https", host);
            } else if (scheme.equals("http") || scheme.equals("https")) {
                addOrigin(out, scheme, host);
            } else {
                fallback = true;
            }
        }
        if (fallback || out.isEmpty()) {
            out.clear();
            out.add("*");
        }
        return out;
    }

    public static boolean isAllSites(String pattern) {
        if (pattern == null) {
            return false;
        }
        String p = pattern.trim().toLowerCase(java.util.Locale.US);
        return p.equals("*") || p.equals("<all_urls>") || p.equals("http://*") || p.equals("https://*")
                || p.equals("*://*") || p.equals("http://*/*") || p.equals("https://*/*") || p.equals("*://*/*")
                || p.equals("^https?://.*") || p.equals("/^https?:\\/\\/.*$/");
    }

    public static boolean hasAllSites(List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        for (String p : patterns) {
            if (isAllSites(p)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNoMatch(List<String> patterns) {
        return patterns != null && patterns.size() == 1 && NO_MATCH.equals(patterns.get(0));
    }

    private static void addOrigin(Set<String> out, String scheme, String host) {
        if (host == null || host.isEmpty()) {
            out.add("*");
            return;
        }
        int at = host.indexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        if (host.equals("*")) {
            out.add("*");
            return;
        }
        if (host.startsWith("*.")) {
            out.add(scheme + "://" + host);
            return;
        }
        if (host.indexOf('*') >= 0) {
            out.add("*");
            return;
        }
        out.add(scheme + "://" + host);
    }

    public static List<String> toRegexList(List<String> patterns) {
        List<String> out = new ArrayList<>();
        if (patterns == null) {
            return out;
        }
        for (String p : patterns) {
            if (out.size() >= MAX_PATTERN_LIST) break;
            String r = toRegex(p);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    public static List<String> effectiveMatches(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return DEFAULT_MATCHES;
        }
        return patterns;
    }

    public static boolean matchesAny(List<String> regexes, String url) {
        if (url == null || url.length() > 8192 || regexes == null || regexes.isEmpty()) {
            return false;
        }
        int checked = 0;
        for (String r : regexes) {
            if (checked++ >= MAX_PATTERN_LIST) break;
            Pattern p = PATTERN_CACHE.get(r);
            if (p == null) {
                try {
                    p = Pattern.compile(r);
                } catch (Exception e) {
                    continue;
                }
                if (PATTERN_CACHE.size() >= 512) {
                    PATTERN_CACHE.clear();
                }
                Pattern existing = PATTERN_CACHE.putIfAbsent(r, p);
                if (existing != null) {
                    p = existing;
                }
            }
            if (p.matcher(url).find()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTarget(PluginInfo info, String url) {
        if (info == null || url == null) {
            return false;
        }
        return info.isTarget(url);
    }

    public static String quoteRegex(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (SPECIALS.indexOf(c) >= 0 || c == '/' || c == '*' || c == '?' || c == '-') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String globBodyToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                sb.append(quoteRegex(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    public static String fromMatchPattern(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim();
        if (p.isEmpty() || p.length() > 512) {
            return null;
        }
        if (p.equals("<all_urls>")) {
            return "<all_urls>";
        }
        if (p.equals("*")) {
            return "<all_urls>";
        }
        int sep = p.indexOf("://");
        if (sep <= 0) {
            return null;
        }
        String scheme = p.substring(0, sep).toLowerCase(java.util.Locale.US);
        String rest = p.substring(sep + 3);
        String schemeRegex;
        switch (scheme) {
            case "*":
                schemeRegex = "(?:https?|wss?)";
                break;
            case "http":
            case "https":
            case "file":
            case "ftp":
            case "ws":
            case "wss":
                schemeRegex = scheme;
                break;
            default:
                return null;
        }
        int slash = rest.indexOf('/');
        String host;
        String path;
        if (slash < 0) {
            if (scheme.equals("file")) {
                return null;
            }
            host = rest;
            path = "/";
        } else {
            host = rest.substring(0, slash);
            path = rest.substring(slash);
        }
        int wildcardCount = 0;
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) == '*') wildcardCount++;
        }
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '*') wildcardCount++;
        }
        if (wildcardCount > 32) {
            return null;
        }
        String hostRegex;
        if (scheme.equals("file")) {
            hostRegex = "";
        } else {
            if (host.isEmpty()) {
                return null;
            }
            String portPart = "";
            int colon = host.lastIndexOf(':');
            if (colon >= 0 && host.indexOf(']') < colon) {
                portPart = host.substring(colon);
                host = host.substring(0, colon);
            }
            if (host.equals("*")) {
                hostRegex = "[^/:]*";
            } else if (host.startsWith("*.")) {
                hostRegex = "(?:[^/:]*\\.)?" + quoteRegex(host.substring(2));
            } else if (host.contains("*")) {
                return null;
            } else {
                hostRegex = quoteRegex(host);
            }
            if (portPart.isEmpty()) {
                hostRegex += "(?::\\d+)?";
            } else if (portPart.equals(":*")) {
                hostRegex += "(?::\\d+)?";
            } else {
                hostRegex += quoteRegex(portPart);
            }
        }
        String regex = "^" + schemeRegex + ":\\/\\/" + hostRegex + globBodyToRegex(path) + "$";
        try {
            Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
        return "/" + regex + "/";
    }

    public static String intersect(String slashRegexA, String slashRegexB) {
        if (slashRegexA == null || slashRegexB == null) {
            return null;
        }
        String a = innerOf(slashRegexA);
        String b = innerOf(slashRegexB);
        if (a == null || b == null) {
            return null;
        }
        String regex = "^(?=" + a + ")(?=" + b + ")";
        try {
            Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
        return "/" + regex + "/";
    }

    private static String innerOf(String p) {
        if (p.equals("<all_urls>")) {
            return "^(?:https?|file):\\/\\/.*$";
        }
        if (p.length() > 2 && p.startsWith("/") && p.endsWith("/")) {
            return p.substring(1, p.length() - 1);
        }
        String r = toRegex(p);
        return r;
    }
}
