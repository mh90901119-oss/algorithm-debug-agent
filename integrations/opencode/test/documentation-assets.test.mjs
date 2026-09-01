import assert from "node:assert/strict"
import { readdir, readFile, stat } from "node:fs/promises"
import path from "node:path"
import test from "node:test"
import { fileURLToPath } from "node:url"

const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url))
const ignoredDirectories = new Set([".git", "node_modules", "target"])

async function markdownFiles(directory) {
  const files = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && ignoredDirectories.has(entry.name)) continue
    const absolute = path.join(directory, entry.name)
    if (entry.isDirectory()) files.push(...await markdownFiles(absolute))
    if (entry.isFile() && entry.name.endsWith(".md")) files.push(absolute)
  }
  return files
}

test("current documentation has no stale paths, counts, or mojibake", async () => {
  const forbidden = [
    /analyses\/<analysisId>\/input\/algorithm-input\.json/u,
    /runs\/<runId>\/raw\/gantt\.json/u,
    /collections\/<collectionId>\/raw\/gantt\.json/u,
    /9 个真实 OpenCode/u,
    /wafer-demo-v1/u,
    /公司/u,
    /锛|銆|鈥|鐨勫|鏂囨。|瀹炵幇/u,
  ]

  for (const file of await markdownFiles(repositoryRoot)) {
    const text = await readFile(file, "utf8")
    for (const pattern of forbidden) {
      assert.doesNotMatch(text, pattern, `${path.relative(repositoryRoot, file)} contains ${pattern}`)
    }
  }
})

test("all repository-local Markdown links resolve", async () => {
  const missing = []
  const linkPattern = /\[[^\]]+\]\(([^)\s]+\.md)(?:#[^)]+)?\)/gu

  for (const file of await markdownFiles(repositoryRoot)) {
    const text = await readFile(file, "utf8")
    for (const match of text.matchAll(linkPattern)) {
      const target = match[1]
      if (/^[a-z]+:/iu.test(target)) continue
      const absolute = path.resolve(path.dirname(file), decodeURIComponent(target))
      try {
        if (!(await stat(absolute)).isFile()) missing.push(`${path.relative(repositoryRoot, file)} -> ${target}`)
      } catch {
        missing.push(`${path.relative(repositoryRoot, file)} -> ${target}`)
      }
    }
  }

  assert.deepEqual(missing, [])
})

