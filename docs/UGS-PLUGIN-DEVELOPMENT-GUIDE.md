# Universal Gcode Sender (UGS) Plugin Development & Deployment Guide

This guide is designed for developers and AI agents working on this repository or building new plugins for **Universal Gcode Sender (UGS) Platform**. It details the architecture, build system, APIs, standalone testing harness, multi-tab patterns, and packaging pipeline based directly on the **G-BASHWARE CamerAim** codebase.

---

## 1. Architecture Overview

UGS Platform is an extensible CNC motion controller built on the **NetBeans Rich Client Platform (RCP)** and **Java Swing**.

* **Target UGS Version:** UGS Platform `2.1.6`
* **Target Java Version:** Java 17+ (tested up to JDK 22)
* **Build System:** Apache Maven with `nbm-maven-plugin` (packaging: `nbm` — NetBeans Module)
* **Core Libraries:**
  * `com.willwinder.universalgcodesender:ugs-core`: Machine controller, backend engine, events, G-code processing.
  * `com.willwinder:ugs-platform-ugslib`: NetBeans platform integration bridge, lookup utilities, UI services.
  * `org.netbeans.api:*`: NetBeans RCP window system (`TopComponent`), lookup (`CentralLookup`), preferences.

---

## 2. Repository Layout

```text
├── .upstream/                          # Cloned UGS source & extracted binaries (excluded from git)
│   ├── ugs/                            # UGS source repo (branch v2.1.6)
│   └── ugs-platform-app-2.1.6/         # Extracted UGS application distribution
├── .m2/repository/                     # Local Maven repo holding custom upstream jars
├── ugs-camera-monitor/                 # The Java plugin Maven module
│   ├── pom.xml                         # NetBeans module definition and dependencies
│   └── src/
│       ├── main/java/com/bashworks/ugs/camera/
│       │   ├── CameraMonitorTopComponent.java  # NetBeans TopComponent (Window entrypoint)
│       │   ├── PluginInfo.java                 # Version & title constants
│       │   ├── capture/                        # Camera capture services & backends
│       │   ├── machine/                        # MachineGateway & UgsMachineGateway
│       │   ├── model/                          # Position, unit, and offset domain models
│       │   └── ui/                             # Swing panels, canvas renderer, controls
│       └── test/java/com/bashworks/ugs/camera/
│           └── ui/CameraMonitorStandalone.java # Standalone Swing preview (no UGS needed)
├── dist/                               # Build output (.nbm, .zip, SHA256SUMS.txt, update-center/)
├── build.ps1                           # Compiles the NBM using local Maven repository
├── package.ps1                         # Builds, signs/hashes, and creates release packages & update center
├── preview.ps1 / preview.bat           # Instant standalone UI preview runner (fast dev cycle)
└── docs/
    ├── INSTALL.md                      # End-user install & calibration guide
    └── UGS-PLUGIN-DEVELOPMENT-GUIDE.md  # This document
```

---

## 3. Environment Setup & Upstream Dependencies

Because UGS artifacts are not all published on Maven Central, this repository relies on a local upstream cache:

### 3.1 Required Setup (Bootstrap Script)
If starting on a fresh machine or CI environment, prepare dependencies as follows (see `.github/workflows/build-release.yml`):

1. **Clone UGS source** (provides the parent POM):
   ```bash
   git clone --depth 1 --branch "v2.1.6" --filter=blob:none https://github.com/winder/Universal-G-Code-Sender.git .upstream/ugs
   ```
2. **Download & extract UGS 2.1.6 distribution**:
   ```bash
   curl -L "https://github.com/winder/Universal-G-Code-Sender/releases/download/v2.1.6/ugs-platform-app-2.1.6.zip" -o ".upstream/ugs-platform-app-2.1.6.zip"
   unzip .upstream/ugs-platform-app-2.1.6.zip -d .upstream/ugs-platform-app-2.1.6
   ```
3. **Install UGS Core and Platform Lib into local Maven repo (`.m2/repository`)**:
   ```bash
   core_jar=".upstream/ugs-platform-app-2.1.6/ugsplatform/ugsplatform/modules/ext/com.willwinder.ugs-platform-ugslib/com-willwinder-universalgcodesender/ugs-core.jar"
   ugslib_jar=".upstream/ugs-platform-app-2.1.6/ugsplatform/ugsplatform/modules/com-willwinder-ugs-platform-ugslib.jar"

   mvn install:install-file -Dfile="$core_jar" -DgroupId=com.willwinder.universalgcodesender -DartifactId=ugs-core -Dversion=2.0-SNAPSHOT -Dpackaging=jar -DgeneratePom=true -DlocalRepositoryPath=.m2/repository
   mvn install:install-file -Dfile="$ugslib_jar" -DgroupId=com.willwinder -DartifactId=ugs-platform-ugslib -Dversion=2.0-SNAPSHOT -Dpackaging=jar -DgeneratePom=true -DlocalRepositoryPath=.m2/repository
   ```

---

## 4. Plugin Anatomy & NetBeans Integration

### 4.1 Module Descriptor (`pom.xml`)
The plugin module must inherit from `ugs-platform-parent` and have `<packaging>nbm</packaging>`:

```xml
<parent>
    <groupId>com.willwinder</groupId>
    <artifactId>ugs-platform-parent</artifactId>
    <version>${revision}${changelist}</version>
    <relativePath>../.upstream/ugs/ugs-platform/pom.xml</relativePath>
</parent>

<artifactId>ugs-platform-plugin-camera-monitor</artifactId>
<version>2.2.0</version>
<packaging>nbm</packaging>
```

### 4.2 TopComponent (Window Entry Point)
Every window or panel in UGS is a `TopComponent`. Annotate your class to register it into the UGS window manager and menu:

```java
package com.bashworks.ugs.camera;

import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.windows.TopComponent;
import com.willwinder.nbp.lib.Mode;
import com.willwinder.nbp.lib.services.LocalizingService;

@TopComponent.Description(
        preferredID = "CameraMonitorTopComponent",
        persistenceType = TopComponent.PERSISTENCE_ALWAYS)
@TopComponent.Registration(
        mode = Mode.EDITOR_SECONDARY,   // Dock position: EDITOR_SECONDARY, LEFT, RIGHT, etc.
        openAtStartup = true,
        position = 500)
@ActionID(
        category = LocalizingService.CATEGORY_WINDOW,
        id = "com.bashworks.ugs.camera.CameraMonitorTopComponent")
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(
        displayName = "G-BASHWARE CamerAim",
        preferredID = "CameraMonitorTopComponent")
public final class CameraMonitorTopComponent extends TopComponent implements UGSEventListener {

    public CameraMonitorTopComponent() {
        setLayout(new BorderLayout());
        add(new MyPluginPanel(), BorderLayout.CENTER);
        refreshTitle();
    }

    @Override
    protected void componentOpened() {
        refreshTitle();
        backend.addUGSEventListener(this);
    }

    @Override
    protected void componentClosed() {
        backend.removeUGSEventListener(this);
    }

    private void refreshTitle() {
        // Crucial: NetBeans window manager can restore a stale title from cache.
        setName("My Plugin Name");
        setDisplayName("My Plugin Name");
        setHtmlDisplayName(null);
    }
}
```

### 4.3 Accessing UGS Backend via `CentralLookup`
UGS uses NetBeans Lookup to provide singletons:

```java
import com.willwinder.ugs.nbp.lib.lookup.CentralLookup;
import com.willwinder.universalgcodesender.model.BackendAPI;

BackendAPI backend = CentralLookup.getDefault().lookup(BackendAPI.class);
```

### 4.4 Machine State & Event Handling
Always decouple UGS `BackendAPI` from UI components by using an interface (e.g. `MachineGateway`):

```java
// Listen for controller state, position changes, or alarms:
@Override
public void UGSEvent(UGSEvent event) {
    // ALWAYS dispatch UI updates to Swing EDT:
    SwingUtilities.invokeLater(() -> panel.refreshMachineState());
}
```

**Safety Lockout Rules:** Before commanding any motion or zeroing:
```java
public boolean canJogOrMove() {
    return backend.isConnected()
        && backend.isIdle()
        && backend.getControllerState() == ControllerState.IDLE
        && !backend.isSendingFile()
        && !backend.isPaused()
        && backend.getController() != null
        && backend.getController().getCapabilities().hasJogging();
}
```

### 4.5 Motion & Coordinates API
* **Jog / Relative Move**:
  ```java
  backend.adjustManualLocation(
      new PartialPosition(deltaX, deltaY, UnitUtils.Units.MM),
      feedRate
  );
  ```
* **Set Work Zero**:
  ```java
  backend.setWorkPosition(new PartialPosition(0.0, 0.0, UnitUtils.Units.MM));
  ```
* **Send G-Code Command**:
  ```java
  backend.sendGcodeCommand(new GcodeCommand("$HZ\n"));
  ```

### 4.6 Preferences / Settings
To support running both inside UGS and in standalone test mode without exceptions:
```java
private static Preferences initPreferences() {
    try {
        Class<?> nbPrefClass = Class.forName("org.openide.util.NbPreferences");
        return (Preferences) nbPrefClass.getMethod("forModule", Class.class).invoke(null, MyClass.class);
    } catch (Throwable fallback) {
        return Preferences.userNodeForPackage(MyClass.class);
    }
}
```

---

## 5. Multi-Tab Architecture & Combining Plugins

To combine several plugins or tools into one package, use one of the two strategies:

### Strategy 1: Single TopComponent with `JTabbedPane` (Internal Tabs)
Best for unified tool suites (e.g. CamerAim + Auto-Probe + Macro Pad):

```text
┌────────────────────────────────────────────────────────┐
│  My CNC Suite (TopComponent)                           │
│ ┌──────────────┬──────────────┬──────────────┐         │
│ │ CamerAim [x] │ Probing      │ Macros       │         │
│ ├──────────────┴──────────────┴──────────────┤         │
│ │                                            │         │
│ │   [Active Tab Component: CameraMonitorPanel]│        │
│ │                                            │         │
│ └────────────────────────────────────────────┘         │
└────────────────────────────────────────────────────────┘
```

1. Create a master `TopComponent`.
2. Add a `JTabbedPane`:
   ```java
   JTabbedPane tabs = new JTabbedPane();
   tabs.addTab("Camera", cameraPanel);
   tabs.addTab("Probing", probePanel);
   tabs.addTab("Macros", macroPanel);
   add(tabs, BorderLayout.CENTER);
   ```
3. Forward `UGSEvent` notifications to active child panels.
4. **Performance optimization:** Add a `ChangeListener` to `JTabbedPane` to pause heavy background loops (e.g., stop camera frame polling when switching to the Probing tab).

### Strategy 2: Multi-TopComponent Module (Native UGS Docking Tabs)
A single `.nbm` module can export multiple `TopComponent` classes. If you register them with the same `mode` (e.g., `Mode.EDITOR_SECONDARY`) and adjacent positions, UGS will natively dock them as tabs in the same window pane, while still allowing the user to un-dock or split them.

---

## 6. Fast Development Workflow (Standalone Runner)

Launching the full UGS Platform to test minor UI tweaks or button handlers is slow. This repo includes a standalone runner pattern:

1. **`MachineGateway` Interface**: Abstracts UGS `BackendAPI`.
2. **`SimulatedMachine`**: In-memory mock implementation of `MachineGateway` in `CameraMonitorStandalone.java`.
3. **Run Standalone Preview**:
   ```powershell
   .\preview.ps1
   ```
   Or double-click `preview.bat`. This uses `javac` directly into `.verify\standalone-classes` and runs the Swing frame in under 2 seconds.

---

## 7. Building & Packaging

### 7.1 Local Build
To compile the plugin module and generate the `.nbm`:
```powershell
.\build.ps1 -JavaHome "C:\Program Files\Java\jdk-22" -Maven "C:\java\maven\bin\mvn.cmd"
```
Output: `ugs-camera-monitor\target\nbm\ugs-platform-plugin-camera-monitor-2.2.0.nbm`.

### 7.2 Full Packaging & Update-Center Generation
To build the distribution ZIP, checksums, and NetBeans update-center catalog:
```powershell
.\package.ps1
```
Output files in `dist/`:
* `G-BASHWARE-CamerAim-2.2.0_for-UGS-2.1.6.nbm` — Ready for manual installation.
* `G-BASHWARE-CamerAim-2.2.0_UGS-2.1.6_Windows.zip` — Packaged release archive with installation docs.
* `SHA256SUMS.txt` — Cryptographic verification hashes.
* `update-center/updates.xml` — NetBeans autoupdate catalog for automatic in-app updates.

---

## 8. Installing in UGS Platform

1. In UGS Platform, navigate to: **Tools → Plugins → Downloaded**.
2. Click **Add Plugins...**, select the `.nbm` file.
3. Click **Install**, follow the wizard, and restart UGS.
4. The plugin window will open automatically or can be opened via:
   **Window → Plugins → [Plugin Display Name]**.

---

## 9. Critical Rules & Guardrails for Agents

1. **Never block the Swing Event Dispatch Thread (EDT):**
   * Long-running operations (camera capture, serial communication, probing loops) must run on worker threads or background processes.
   * All modifications to Swing UI components must happen on the EDT via `SwingUtilities.invokeLater(...)`.
2. **Crash Isolation:**
   * If integrating native libraries (OpenCV, DirectShow, MediaFoundation, JNI/JNA), follow CamerAim's pattern of isolating the native work in a spawned sub-process (`CameraHelperProcess.java`). If native code faults, it won't crash the entire UGS application mid-job.
3. **Strict Machine Safety:**
   * Never execute motion without checking `canJogOrMove()`.
   * Clear error messages must be provided when motion is blocked (e.g. "Machine is in Alarm state", "Job is currently streaming").
4. **Preserve Relative Paths:**
   * `ugs-camera-monitor/pom.xml` links to `../.upstream/ugs/ugs-platform/pom.xml`. Do not alter this relative path unless moving the directory layout.
