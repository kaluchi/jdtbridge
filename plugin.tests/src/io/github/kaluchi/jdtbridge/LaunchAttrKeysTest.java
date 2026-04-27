package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.junit.jupiter.api.Test;

/**
 * Pin the launch-config attribute key strings used as switch case
 * labels in {@link LaunchHandler} / {@link ProjectScope} to their
 * SDK equivalents. The plugin can't alias them directly because
 * the SDK constants are computed at class init via plugin-id
 * concatenation, so {@code switch} sees them as non-constant.
 * Tests run on the live Eclipse, so any future SDK key rename
 * surfaces here before it bites a user.
 */
public class LaunchAttrKeysTest {

    @Test
    void attrProjectName() {
        assertEquals(
                IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                "org.eclipse.jdt.launching.PROJECT_ATTR");
    }

    @Test
    void attrMainTypeName() {
        assertEquals(
                IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                "org.eclipse.jdt.launching.MAIN_TYPE");
    }

    @Test
    void attrProgramArguments() {
        assertEquals(
                IJavaLaunchConfigurationConstants
                        .ATTR_PROGRAM_ARGUMENTS,
                "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS");
    }

    @Test
    void attrVmArguments() {
        assertEquals(
                IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
                "org.eclipse.jdt.launching.VM_ARGUMENTS");
    }

    @Test
    void attrWorkingDirectory() {
        assertEquals(
                IJavaLaunchConfigurationConstants
                        .ATTR_WORKING_DIRECTORY,
                "org.eclipse.jdt.launching.WORKING_DIRECTORY");
    }

    @Test
    void idJavaApplication() {
        assertEquals(
                IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION,
                "org.eclipse.jdt.launching.localJavaApplication");
    }
}
