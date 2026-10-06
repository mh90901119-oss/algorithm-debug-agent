/**
 * 只读检查本设计包的链接、示例不变量、已有本地路径与规格编号。
 * 不生成/修改产品资产，不替代 Schema、Mermaid 渲染或产品行为测试。
 */
import assert from 'node:assert/strict';
import { readFile, readdir, stat } from 'node:fs/promises';
import { dirname, extname, resolve, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE_ROOT = dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = resolve(PACKAGE_ROOT, '../../..');
const PORTABLE_ONLY = process.argv.slice(2).includes('--portable');
assert.ok(process.argv.slice(2).every(value => value === '--portable'), '只支持 --portable 参数');
const DOC_EXTENSION = '.md';
const JSON_EXTENSION = '.json';
const HASH_PATTERN = /^[0-9a-f]{64}$/u;
const LINK_PATTERN = /\[[^\]\n]+\]\(([^)\n]+)\)/gu;
const REFERENCE_PATTERN = /\b(?:G\d+|T\d{2}|E\d{2}|P\d+)\b/gu;
const VALID_PURPOSES = new Set(['DISCOVERY', 'VERIFICATION']);
const VALID_TRUTHS = new Set(['TRUE', 'FALSE', 'UNKNOWN']);
const VALID_EFFECTS = new Set(['SUPPORT', 'REFUTE', 'NO_CHANGE']);
const files = [];
const markdown = new Map();
let linksChecked = 0;
let localPathsChecked = 0;
let jsonChecked = 0;

async function collect(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
    const path = resolve(directory, entry.name);
    assert.ok(!entry.isSymbolicLink(), `设计包不能越界读取符号链接: ${entry.name}`);
    if (entry.isDirectory()) await collect(path);
    else if (entry.isFile()) files.push(path);
  }
}

function inside(root, candidate) {
  const path = relative(root, candidate);
  return path !== '..' && !path.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`)
    && !isAbsolute(path);
}

async function requireFile(path) {
  assert.ok(inside(PORTABLE_ONLY ? PACKAGE_ROOT : REPOSITORY_ROOT, path), `路径越出验证范围: ${path}`);
  assert.ok((await stat(path)).isFile(), `引用不是文件: ${path}`);
}

await collect(PACKAGE_ROOT);
for (const path of files) {
  const extension = extname(path);
  if (![DOC_EXTENSION, JSON_EXTENSION].includes(extension)) continue;
  const content = await readFile(path, 'utf8');
  assert.ok(!content.includes('\uFFFD'), `文本包含替换字符: ${path}`);
  const lines = content.split(/\r?\n/u);
  lines.forEach((line, index) => assert.ok(!/[ \t]+$/u.test(line), `行末空白: ${path}:${index + 1}`));
  if (extension === JSON_EXTENSION) {
    JSON.parse(content);
    jsonChecked += 1;
    continue;
  }
  markdown.set(path, content);
  let fence = null;
  let body = [];
  for (const line of lines) {
    if (line.startsWith('```')) {
      if (fence === null) {
        fence = line.slice(3).trim();
        body = [];
      } else {
        if (fence === 'json') JSON.parse(body.join('\n'));
        if (fence === 'mermaid') assert.ok(body.some(value => value.trim().length > 0), `空图: ${path}`);
        fence = null;
      }
    } else if (fence !== null) body.push(line);
  }
  assert.equal(fence, null, `围栏未闭合: ${path}`);
  for (const match of content.matchAll(LINK_PATTERN)) {
    const target = match[1].trim().replace(/^<|>$/gu, '');
    if (/^(?:https?:|#)/u.test(target)) continue;
    assert.ok(!target.includes(' '), `未处理的链接空格: ${target}`);
    await requireFile(resolve(dirname(path), decodeURIComponent(target.split('#')[0])));
    linksChecked += 1;
  }
}

const readJson = async name => JSON.parse(await readFile(resolve(PACKAGE_ROOT, name), 'utf8'));
const fileMap = await readJson('examples/local-file-map.json');
assert.equal(fileMap.scope, 'LOCAL_BASELINE_ONLY_NOT_COMPANY_FILE_NAMES');
assert.match(fileMap.baselineCommit, /^[0-9a-f]{40}$/u);
const existingPaths = fileMap.groups.flatMap(group => group.classes.map(name => `${group.base}/${name}.java`));
existingPaths.push(...fileMap.files);
assert.equal(new Set(existingPaths).size, existingPaths.length, '本地已有文件列表重复');
if (!PORTABLE_ONLY) {
  for (const path of existingPaths) {
    await requireFile(resolve(REPOSITORY_ROOT, path));
    localPathsChecked += 1;
  }
}
const manifest = PORTABLE_ONLY ? { tools: fileMap.baselineToolNames }
  : JSON.parse(await readFile(resolve(REPOSITORY_ROOT, 'agent-definition/capability-manifest-v1.json'), 'utf8'));
assert.deepEqual([...fileMap.baselineToolNames].sort(), [...manifest.tools].sort(), '审计快照与本地Catalog发布清单不一致');

const binding = await readJson('examples/binding-discovery.json');
assert.equal(binding.schemaVersion, '2.0');
assert.ok(VALID_PURPOSES.has(binding.purpose));
assert.equal(binding.purpose, 'DISCOVERY');
assert.equal(binding.hypothesisIds.length, 0);
assert.equal(binding.predicateIds.length, 0);

const semantics = await readJson('examples/observation-semantics.json');
for (const row of semantics.cases) {
  assert.ok(VALID_TRUTHS.has(row.truth));
  for (const field of ['onTrue', 'onFalse', 'onUnknown', 'appliedEffect']) assert.ok(VALID_EFFECTS.has(row[field]));
  assert.equal(row.onUnknown, 'NO_CHANGE');
  const mapping = { TRUE: row.onTrue, FALSE: row.onFalse, UNKNOWN: 'NO_CHANGE' };
  const mayApply = row.disposition === 'CONFIRMATION_ELIGIBLE' && row.coverage === 'COMPLETE' && row.truth !== 'UNKNOWN';
  const expected = mayApply ? mapping[row.truth] : 'NO_CHANGE';
  assert.equal(row.appliedEffect, expected, row.name);
  assert.equal(row.effectApplied, expected !== 'NO_CHANGE', row.name);
}

const rejection = await readJson('examples/tool-rejection.json');
assert.equal(rejection.outcome, 'REJECTED');
assert.equal(rejection.control.decision, 'REJECTED');
assert.equal(rejection.artifacts.length, 0);
assert.match(rejection.control.snapshotToken, HASH_PATTERN);
assert.ok(!Object.hasOwn(rejection.control, 'terminalEligibility'));
for (const action of rejection.recoveryActions) {
  assert.ok(manifest.tools.includes(action.toolName), `不存在的恢复工具: ${action.toolName}`);
  assert.equal(action.arguments.caseId, rejection.control.identity.caseId);
  assert.equal(action.arguments.analysisId, rejection.control.identity.analysisId);
}

const goalsText = markdown.get(resolve(PACKAGE_ROOT, '01-goals-and-interaction.md'));
const testsText = markdown.get(resolve(PACKAGE_ROOT, '05-tests-and-acceptance.md'));
const planText = markdown.get(resolve(PACKAGE_ROOT, '06-implementation-plan.md'));
const definitions = new Set([
  ...[...goalsText.matchAll(/^\| (G\d+) \|/gmu)].map(value => value[1]),
  ...[...testsText.matchAll(/^\| (T\d{2}) \/ /gmu)].map(value => value[1]),
  ...[...testsText.matchAll(/^### .* (E\d{2})$/gmu)].map(value => value[1]),
  ...[...planText.matchAll(/^### (P\d+)：/gmu)].map(value => value[1])
]);
for (const [path, content] of markdown) {
  for (const match of content.matchAll(REFERENCE_PATTERN)) assert.ok(definitions.has(match[0]), `未定义规格编号 ${match[0]}: ${path}`);
}
assert.ok(definitions.has('G10') && definitions.has('T23') && definitions.has('E03') && definitions.has('P9'), '规格目录不完整');

console.log(JSON.stringify({
  status: 'DOC_PACKAGE_CHECK_PASSED',
  mode: PORTABLE_ONLY ? 'PORTABLE_DOCUMENTS_ONLY' : 'LOCAL_BASELINE_AND_DOCUMENTS',
  markdownFiles: markdown.size,
  jsonFiles: jsonChecked,
  linksChecked,
  localPathsChecked,
  specificationIds: definitions.size,
  limitations: ['不是完整JSON Schema验证', '未执行Mermaid渲染', '未执行生产行为/真实宿主/模型质量测试']
}, null, 2));
