package de.systemlogistics.slgos.reference;

import de.systemlogistics.slgos.sdk.ContractVersion;
import org.springframework.stereotype.Component;

/** Identity of the reference module. Placeholder until the manifest and permissions are built (PRD-0003 SK-13). */
@Component
public class ModuleDescriptor {

    public String id() {
        return "reference";
    }

    public String contractVersion() {
        return ContractVersion.CURRENT;
    }
}
