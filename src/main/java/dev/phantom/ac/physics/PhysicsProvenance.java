package dev.phantom.ac.physics;

import java.io.Serializable;
import java.util.Objects;

/**
 * Provenance for a version-pinned physics value.
 *
 * The reference is intentionally a human-maintained source/trace reference rather than
 * a dependency on another anticheat implementation.
 */
public record PhysicsProvenance(
    String version,
    String sourceReference,
    String verificationStatus,
    String notes
) implements Serializable {
  public PhysicsProvenance {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(sourceReference, "sourceReference");
    Objects.requireNonNull(verificationStatus, "verificationStatus");
    Objects.requireNonNull(notes, "notes");
  }

  public static PhysicsProvenance unverified(String version, String notes) {
    return new PhysicsProvenance(version, "UNRECORDED", "UNVERIFIED", notes);
  }
}
