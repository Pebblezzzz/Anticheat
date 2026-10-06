package dev.phantom.ac;

import dev.phantom.ac.paper.HardenedPhantomPaperPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardenedPhantomPaperPluginTest {

  @Test
  void autoclickerAlertPolicyOverridesLegacyTwentyFortyThreshold() {
    GrimAlertPolicy.CommandRule legacy =
        GrimAlertPolicy.CommandRule.parse("20:40");

    GrimAlertPolicy.CommandRule effective =
        HardenedPhantomPaperPlugin.effectiveAlertRule(false, "Autoclicker", legacy);

    assertEquals(1.0, effective.threshold(), 1.0e-9);
    assertEquals(1.0, effective.interval(), 1.0e-9);
  }

  @Test
  void nonAutoclickerLegacyPolicyRemainsConfigurable() {
    GrimAlertPolicy.CommandRule legacy =
        GrimAlertPolicy.CommandRule.parse("20:40");

    GrimAlertPolicy.CommandRule effective =
        HardenedPhantomPaperPlugin.effectiveAlertRule(false, "BadPackets", legacy);

    assertEquals(20.0, effective.threshold(), 1.0e-9);
    assertEquals(40.0, effective.interval(), 1.0e-9);
  }
}
