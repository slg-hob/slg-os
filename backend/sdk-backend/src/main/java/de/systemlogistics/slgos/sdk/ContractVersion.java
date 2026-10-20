package de.systemlogistics.slgos.sdk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Module contract version supported by this SDK (PRD-0001 FR-6, Q13: only the current major is supported).
 */
public final class ContractVersion {

    /** The contract version this SDK implements. */
    public static final String CURRENT = "1.0";

    private static final Pattern VERSION = Pattern.compile("^(\\d{1,4})\\.(\\d{1,5})$");
    private static final int CURRENT_MAJOR = 1;

    private ContractVersion() {
    }

    /**
     * Tells whether a manifest's declared contract version belongs to the supported major.
     *
     * @param declared a version of the form {@code major.minor}
     * @return true if the major equals the current one and the minor is not newer than this SDK knows
     */
    public static boolean isSupported(String declared) {
        if (declared == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(declared);
        if (!matcher.matches()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major == CURRENT_MAJOR && minor <= currentMinor();
    }

    private static int currentMinor() {
        return Integer.parseInt(CURRENT.substring(CURRENT.indexOf('.') + 1));
    }
}
