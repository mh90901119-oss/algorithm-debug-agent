import { fileURLToPath } from "node:url"

// 仓库内直接验证时使用；一次性安装器会生成指向实际仓库的同名模块。
export const defaultLauncher = fileURLToPath(new URL("../../../bin/ada.cmd", import.meta.url))
