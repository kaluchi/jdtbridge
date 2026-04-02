package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps agent sessions to {@link ProjectScope}.
 * <p>
 * Resolves the session file's {@code workingDir} to a set of
 * workspace projects on every request — no caching.
 * This ensures new/removed projects are visible immediately.
 * <p>
 * Returns {@link ProjectScope#ALL} when no session or no
 * filtering is needed — never returns null.
 */
class SessionScope {

    /**
     * Resolve the project scope for a session.
     *
     * @param bridgeSessionId the session identifier from
     *                  {@code X-Bridge-Session} header, may be null
     * @return project scope, never null
     */
    ProjectScope resolve(String bridgeSessionId) {
        if (bridgeSessionId == null
                || bridgeSessionId.isEmpty()) {
            return ProjectScope.ALL;
        }
        return load(bridgeSessionId);
    }

    private ProjectScope load(String bridgeSessionId) {
        Path sessionFile = Activator.getHome()
                .resolve("sessions")
                .resolve(bridgeSessionId + ".json");
        if (!Files.exists(sessionFile)) {
            return ProjectScope.ALL;
        }

        String workingDir;
        try {
            String json = Files.readString(sessionFile);
            JsonObject obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            workingDir = obj.has("workingDir")
                    ? obj.get("workingDir").getAsString()
                    : null;
        } catch (IOException | RuntimeException e) {
            Log.warn("Failed to read session file: "
                    + sessionFile, e);
            return ProjectScope.ALL;
        }

        if (workingDir == null || workingDir.isBlank()) {
            return ProjectScope.ALL;
        }

        return resolveProjects(workingDir);
    }

    private ProjectScope resolveProjects(String workingDir) {
        Path workDir = normalize(Path.of(workingDir));

        Set<String> projects = ProjectScope.allOpenProjects()
                .filter(p -> p.getLocation() != null)
                .filter(p -> normalize(Path.of(
                        p.getLocation().toOSString()))
                        .startsWith(workDir))
                .map(p -> p.getName())
                .collect(Collectors.toUnmodifiableSet());

        return ProjectScope.of(projects);
    }

    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
