package de.systemlogistics.slgos.core.system;

import de.systemlogistics.slgos.sdk.ContractVersion;
import org.springframework.stereotype.Component;

/** Describes what this core build supports. Placeholder that proves the module wiring; replaced by real features. */
@Component
public class SystemInfo {

    /** The module contract version the core accepts in manifests. */
    public String contractVersion() {
        return ContractVersion.CURRENT;
    }

    /** Tells whether a manifest with the given contract version may be registered. */
    public boolean accepts(String declaredContractVersion) {
        return ContractVersion.isSupported(declaredContractVersion);
    }
}
