# G-BASHWARE CamerAim module

This directory contains the NetBeans module used by G-BASHWARE CamerAim. The public project overview, installation guide, and roadmap are maintained in the [repository README](../README.md).

## Build

Run `build.ps1` from the repository root after preparing the local UGS 2.1.6 source and Maven repository. The build runs nine unit tests and writes the installable module to:

`target/nbm/ugs-platform-plugin-camera-monitor-2.1.0.nbm`

The artifact ID and NetBeans code-name base intentionally retain the original Camera Monitor prototype identity. This lets the branded package update an existing installation instead of appearing as a duplicate module.

The GitHub Actions workflow prepares the upstream UGS dependencies from a clean checkout, builds the module, packages the release downloads, and generates the NetBeans update catalog.
