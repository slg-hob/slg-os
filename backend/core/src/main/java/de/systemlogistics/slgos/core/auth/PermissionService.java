package de.systemlogistics.slgos.core.auth;

import de.systemlogistics.slgos.core.config.SlgProperties;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Effective permissions of a user. Milestone M2 knows only the bootstrap administrator group; the permission tables and
 * the admin API follow in M3a (PRD-0003 SK-6) and replace the body of this class. Absence means deny.
 */
@Service
public class PermissionService {

    public static final String PLATFORM_ADMIN = "platform-admin";

    private final String bootstrapAdminGroup;

    public PermissionService(SlgProperties properties) {
        String group = properties.bootstrapAdminGroup();
        this.bootstrapAdminGroup = group == null || group.isBlank() ? null : group;
    }

    /** The permission names for the given group paths. */
    public Set<String> effective(List<String> groups) {
        if (bootstrapAdminGroup != null && groups.contains(bootstrapAdminGroup)) {
            return Set.of(PLATFORM_ADMIN);
        }
        return Set.of();
    }
}
