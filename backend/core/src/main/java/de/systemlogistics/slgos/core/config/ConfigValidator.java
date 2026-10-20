package de.systemlogistics.slgos.core.config;

import de.systemlogistics.slgos.core.config.ConfigurationProblemException.ConfigProblem;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks the mandatory settings at start and names every invalid one (PRD-0003 SK-16 AC5, SK-15 AC3, section 8).
 * Messages are operator messages in German and English and never contain secret values.
 */
public final class ConfigValidator {

    private static final Set<String> ENVIRONMENTS = Set.of("dev", "test", "e2e", "production");

    private ConfigValidator() {}

    public static List<ConfigProblem> validate(SlgProperties p) {
        List<ConfigProblem> problems = new ArrayList<>();
        if (!ENVIRONMENTS.contains(p.env())) {
            problems.add(new ConfigProblem("SLG_ENV", "erlaubt sind dev, test, e2e, production",
                    "allowed values are dev, test, e2e, production"));
        }
        boolean lax = "dev".equals(p.env()) || "test".equals(p.env());
        URI shell = absoluteUri("SLG_SHELL_BASE_URL", p.shellBaseUrl(), lax, problems);
        URI issuer = absoluteUri("SLG_OIDC_ISSUER", p.oidc().issuer(), lax, problems);
        if (shell != null && (shell.getRawPath() != null && !shell.getRawPath().isEmpty() || shell.getRawQuery() != null)) {
            problems.add(new ConfigProblem("SLG_SHELL_BASE_URL", "nur Schema, Host und Port, ohne Pfad",
                    "scheme, host and port only, without a path"));
        }
        if (p.production()) {
            checkProductionGuard(shell, issuer, problems);
            if (p.trustedProxyCidrs().isEmpty()) {
                problems.add(new ConfigProblem("SLG_TRUSTED_PROXY_CIDRS",
                        "in Produktion Pflicht, sonst teilen alle Nutzer eine Adresse (Rate-Limit, Audit)",
                        "mandatory in production, otherwise all users share one address (rate limit, audit)"));
            }
            if (p.internalHosts().stream().anyMatch(h -> isTestHost(h) || h.isBlank())) {
                problems.add(new ConfigProblem("SLG_INTERNAL_HOSTS",
                        "in Produktion nur interne Dienstnamen, kein localhost",
                        "in production only internal service names, no localhost"));
            }
        }
        required("SLG_OIDC_CLIENT_ID", p.oidc().clientId(), problems);
        secretFile("SLG_OIDC_CLIENT_SECRET_FILE", p.oidc().clientSecretFile(), problems);
        validateSession(p.session(), problems);
        validateDb(p.db(), problems);
        for (String cidr : p.trustedProxyCidrs()) {
            try {
                Cidr.parse(cidr);
            } catch (IllegalArgumentException e) {
                problems.add(new ConfigProblem("SLG_TRUSTED_PROXY_CIDRS", "ungültiger Netzbereich: " + cidr,
                        "invalid network range: " + cidr));
            }
        }
        return problems;
    }

    /** Throws when a setting is invalid, with the operator message listing all problems. */
    public static void require(SlgProperties p) {
        List<ConfigProblem> problems = validate(p);
        if (!problems.isEmpty()) {
            throw new ConfigurationProblemException(problems);
        }
    }

    private static void checkProductionGuard(URI shell, URI issuer, List<ConfigProblem> problems) {
        guardHost("SLG_SHELL_BASE_URL", shell, problems);
        guardHost("SLG_OIDC_ISSUER", issuer, problems);
        if (issuer != null && issuer.getPath() != null && issuer.getPath().toLowerCase(Locale.ROOT).endsWith("-e2e")) {
            problems.add(new ConfigProblem("SLG_OIDC_ISSUER", "das E2E-Realm ist in Produktion nicht erlaubt",
                    "the e2e realm is not allowed in production"));
        }
    }

    private static void guardHost(String variable, URI uri, List<ConfigProblem> problems) {
        if (uri != null && uri.getHost() != null && isTestHost(uri.getHost())) {
            problems.add(new ConfigProblem(variable, "Test-Hostname in Produktion nicht erlaubt (SLG_ENV=production)",
                    "test host name not allowed in production (SLG_ENV=production)"));
        }
    }

    private static boolean isTestHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".test") || h.endsWith(".localhost") || h.equals("127.0.0.1");
    }

    private static void validateSession(SlgProperties.Session s, List<ConfigProblem> problems) {
        if (s.idleTimeout().isZero() || s.idleTimeout().isNegative()) {
            problems.add(new ConfigProblem("SLG_SESSION_IDLE_TIMEOUT", "muss positiv sein", "must be positive"));
        }
        if (s.absoluteTimeout().compareTo(s.idleTimeout()) < 0) {
            problems.add(new ConfigProblem("SLG_SESSION_ABSOLUTE_TIMEOUT", "darf nicht kleiner als die Leerlaufzeit sein",
                    "must not be shorter than the idle timeout"));
        }
        if (s.maxPerUser() < 1) {
            problems.add(new ConfigProblem("SLG_SESSION_MAX_PER_USER", "mindestens 1", "at least 1"));
        }
        required("SLG_SESSION_VAULT_ACTIVE_KEY_ID", s.vaultActiveKeyId(), problems);
        if (isBlank(s.vaultKeysFile())) {
            problems.add(missing("SLG_SESSION_VAULT_KEYS_FILE"));
        } else if (!readable(s.vaultKeysFile())) {
            problems.add(unreadable("SLG_SESSION_VAULT_KEYS_FILE"));
        } else {
            try {
                Map<String, byte[]> keys = Secrets.parseKeys(Secrets.read(s.vaultKeysFile()));
                if (!isBlank(s.vaultActiveKeyId()) && !keys.containsKey(s.vaultActiveKeyId())) {
                    problems.add(new ConfigProblem("SLG_SESSION_VAULT_ACTIVE_KEY_ID", "Schlüssel-ID nicht in der Schlüsseldatei",
                            "key id not found in the key file"));
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                problems.add(new ConfigProblem("SLG_SESSION_VAULT_KEYS_FILE",
                        "Schlüsseldatei ungültig (Zeilen id=base64, je 32 Byte)",
                        "key file invalid (lines id=base64, 32 bytes each)"));
            }
        }
        if (isBlank(s.csrfKeyFile())) {
            problems.add(missing("SLG_SESSION_CSRF_KEY_FILE"));
        } else if (!readable(s.csrfKeyFile())) {
            problems.add(unreadable("SLG_SESSION_CSRF_KEY_FILE"));
        } else if (Secrets.read(s.csrfKeyFile()).length() < 32) {
            problems.add(new ConfigProblem("SLG_SESSION_CSRF_KEY_FILE", "mindestens 32 Zeichen",
                    "at least 32 characters"));
        }
    }

    private static void validateDb(SlgProperties.Db d, List<ConfigProblem> problems) {
        if (isBlank(d.url())) {
            problems.add(missing("SLG_DB_URL"));
        } else if (!d.url().startsWith("jdbc:postgresql:")) {
            problems.add(new ConfigProblem("SLG_DB_URL", "muss mit jdbc:postgresql: beginnen",
                    "must start with jdbc:postgresql:"));
        }
        required("SLG_DB_APP_USER", d.appUser(), problems);
        required("SLG_DB_MIGRATOR_USER", d.migratorUser(), problems);
        secretFile("SLG_DB_APP_PASSWORD_FILE", d.appPasswordFile(), problems);
        secretFile("SLG_DB_MIGRATOR_PASSWORD_FILE", d.migratorPasswordFile(), problems);
        if (d.poolSize() < 2) {
            problems.add(new ConfigProblem("SLG_DB_POOL_SIZE", "mindestens 2", "at least 2"));
        }
    }

    private static URI absoluteUri(String variable, String value, boolean httpAllowed, List<ConfigProblem> problems) {
        if (isBlank(value)) {
            problems.add(missing(variable));
            return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            boolean ok = "https".equals(scheme) || httpAllowed && "http".equals(scheme);
            if (!ok || uri.getHost() == null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                problems.add(new ConfigProblem(variable,
                        httpAllowed ? "gültige http(s)-URL erwartet" : "gültige https-URL erwartet",
                        httpAllowed ? "a valid http(s) URL is expected" : "a valid https URL is expected"));
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            problems.add(new ConfigProblem(variable, "keine gültige URL", "not a valid URL"));
            return null;
        }
    }

    private static void required(String variable, String value, List<ConfigProblem> problems) {
        if (isBlank(value)) {
            problems.add(missing(variable));
        }
    }

    private static void secretFile(String variable, String file, List<ConfigProblem> problems) {
        if (isBlank(file)) {
            problems.add(missing(variable));
        } else if (!readable(file)) {
            problems.add(unreadable(variable));
        } else if (Secrets.read(file).isEmpty()) {
            problems.add(new ConfigProblem(variable, "Secret-Datei ist leer", "secret file is empty"));
        }
    }

    private static boolean readable(String file) {
        Path path = Path.of(file);
        return Files.isRegularFile(path) && Files.isReadable(path);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ConfigProblem missing(String variable) {
        return new ConfigProblem(variable, "fehlt", "is missing");
    }

    private static ConfigProblem unreadable(String variable) {
        return new ConfigProblem(variable, "Datei nicht lesbar", "file not readable");
    }
}
