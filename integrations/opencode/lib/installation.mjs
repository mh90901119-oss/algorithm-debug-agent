import { readFile } from "node:fs/promises"
import { fileURLToPath } from "node:url"

const settingsUrl = new URL("../../../config/agent-settings.json", import.meta.url)
const settings = JSON.parse(await readFile(settingsUrl, "utf8"))

function expandInstallerVariables(value) {
  return value
    .replaceAll("%USERPROFILE%", process.env.USERPROFILE ?? "")
    .replaceAll("%LOCALAPPDATA%", process.env.LOCALAPPDATA ?? "")
}

export const defaultLauncher = fileURLToPath(new URL("../../../bin/ada.cmd", import.meta.url))
export const openCodeConfigDirectory = expandInstallerVariables(settings.openCodeConfigDirectory)
export const workspaceDirectory = expandInstallerVariables(settings.workspaceDirectory)
export const dfxDirectory = expandInstallerVariables(settings.dfxDirectory)
export const evalDirectory = expandInstallerVariables(settings.evalDirectory)
export const resultJsonDirectory = expandInstallerVariables(settings.resultJsonDirectory)
export const agentJavaHome = expandInstallerVariables(settings.agentJavaHome)
export const targetJavaHome = expandInstallerVariables(settings.targetJavaHome)
export const mavenExecutable = expandInstallerVariables(settings.mavenExecutable)
export const dfxEnabled = settings.dfxEnabled
