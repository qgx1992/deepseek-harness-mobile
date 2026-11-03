/**
 * 补齐历史上只有 tag、没有 Release 的版本。
 *
 * 0.19~0.22.10 发版时只推了 tag 和局域网下载站，GitHub Release 页一直停在
 * v0.18.0。这个脚本按语义版本顺序扫一遍 tag，给缺的版本建 Release：
 * 说明来自 tag 注解 + 两次发版之间的提交，资产是下载站里那一版真实安装过的 APK。
 *
 * 用法：
 *   node scripts/backfill-releases.mjs --dry-run      先看要补哪些
 *   node scripts/backfill-releases.mjs                真发
 *   node scripts/backfill-releases.mjs --from 0.21.0  只补 >= 0.21.0
 *   node scripts/backfill-releases.mjs --no-asset     只建 Release，不传 APK
 */

import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

import {
  publishRelease,
  releaseExists,
  releaseNotes,
  releaseTitle,
  repoSlug,
} from "./github-release.mjs";

const REPO = path.resolve(import.meta.dirname, "..");
const DEFAULT_DIR = path.resolve(REPO, "..", "PiDeck-Mobile", "apk");

const arg = (name, fallback = null) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const flag = (name) => process.argv.includes(`--${name}`);

const targetDir = path.resolve(arg("dir", process.env.DSH_APK_DIR ?? DEFAULT_DIR));
const from = arg("from", null);
const dryRun = flag("dry-run");
const attach = !flag("no-asset");

const compare = (a, b) => {
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  return pa[0] - pb[0] || pa[1] - pb[1] || pa[2] - pb[2];
};

const tags = execFileSync("git", ["tag", "--list", "v*", "--sort=v:refname"], {
  cwd: REPO,
  encoding: "utf8",
})
  .trim()
  .split("\n")
  .filter((name) => /^v\d+\.\d+\.\d+$/.test(name) && name !== "v0.2.0"); // 上古 tag，没有对应安装包

let checked = 0;
let created = 0;
let failed = 0;

console.log(`仓库 ${repoSlug()} · 下载目录 ${targetDir}${dryRun ? " · 只演练" : ""}`);

for (const tag of tags) {
  const version = tag.slice(1);
  if (from && compare(version, from) < 0) continue;

  const apk = path.join(targetDir, `DSH-Mobile-${version}-release.apk`);
  if (attach && !existsSync(apk)) {
    console.log(`- ${tag} 跳过：下载目录里没有 DSH-Mobile-${version}-release.apk`);
    continue;
  }

  checked += 1;
  const title = releaseTitle(version, tag);
  if (releaseExists(tag)) {
    console.log(`· ${tag} 已有 Release，跳过`);
    continue;
  }

  console.log(`+ ${tag} ${title}${attach ? `  ← ${path.basename(apk)}` : ""}`);
  if (dryRun) continue;

  const ok = publishRelease({
    version,
    tag,
    apkPath: attach ? apk : null,
    notes: releaseNotes({ version, tag, apkPath: attach ? apk : null, lanUrl: null }),
    title,
    attach,
  });
  if (ok) {
    created += 1;
    console.log("  已发布");
  } else {
    failed += 1;
    console.error("  失败");
  }
}

console.log(`\n检查 ${checked} 个 tag：新建 ${created}，失败 ${failed}`);
if (failed > 0) process.exitCode = 1;
