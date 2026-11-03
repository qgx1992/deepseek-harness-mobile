/**
 * 把一版 APK 发成 GitHub Release（tag 已存在则补资产、改说明）。
 *
 * publish-apk.mjs 在 --release 时调用它；backfill-releases.mjs 用它补历史上
 * 只有 tag、没有 Release 的版本。
 *
 * 需要的环境（本机都会带）：
 *   gh            已登录（gh auth status 通过）
 *   DSH_GITHUB_PROXY  可选，例如 http://127.0.0.1:7897；设了就给 gh 加 HTTPS_PROXY，
 *                     没设就原样继承当前环境。
 */

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

const REPO = path.resolve(import.meta.dirname, "..");

function env() {
  const base = { ...process.env };
  const proxy = process.env.DSH_GITHUB_PROXY;
  if (proxy) {
    base.HTTPS_PROXY = proxy;
    base.HTTP_PROXY = proxy;
    base.https_proxy = proxy;
    base.http_proxy = proxy;
  }
  return base;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: REPO,
    encoding: "utf8",
    env: env(),
    ...options,
  });
  if (result.error) throw result.error;
  return result;
}

/** owner/repo，从 origin 的 URL 里取，支持 https 与 ssh 两种写法。 */
export function repoSlug() {
  const url = run("git", ["remote", "get-url", "origin"]).stdout.trim();
  const match = /github\.com[:/]([^/]+)\/(.+?)(?:\.git)?$/.exec(url);
  if (!match) throw new Error(`origin 不是 GitHub 仓库：${url}`);
  return `${match[1]}/${match[2]}`;
}

export function releaseExists(tag) {
  const result = run("gh", ["release", "view", tag, "--repo", repoSlug(), "--json", "tagName"]);
  return result.status === 0;
}

export function tagExists(tag) {
  return run("git", ["tag", "--list", tag]).stdout.trim() === tag;
}

function tagSection(tag) {
  const body = run("git", ["tag", "--list", tag, "--format=%(contents:body)"]).stdout.trim();
  return body;
}

function commitsBetween(fromTag, tag, limit = 30) {
  const args = ["log", "--no-merges", "--pretty=- %s"];
  if (fromTag) args.push(`${fromTag}..${tag}`);
  else args.push(`-n${limit}`, tag);
  const out = run("git", args).stdout.trim().split("\n").filter(Boolean);
  return out.slice(0, limit).join("\n");
}

/** 上一个语义版本 tag（没有就返回 null）。 */
export function previousTag(tag) {
  const tags = run("git", ["tag", "--list", "v*", "--sort=v:refname"])
    .stdout.trim()
    .split("\n")
    .filter((name) => /^v\d+\.\d+\.\d+$/.test(name));
  const index = tags.indexOf(tag);
  return index > 0 ? tags[index - 1] : null;
}

export function versionCodeOf(version) {
  const [major, minor, patch] = version.split(".").map(Number);
  return major * 10000 + minor * 100 + patch;
}

export function bytesText(size) {
  return size.toLocaleString("en-US");
}

/** Release 正文：这个包是什么、从哪儿下、校验值多少、这一版改了什么。 */
export function releaseNotes({ version, tag, apkPath, lanUrl }) {
  const tagBody = tagSection(tag);
  const commits = commitsBetween(previousTag(tag), tag);
  const sha256 = apkPath && existsSync(apkPath)
    ? createHash("sha256").update(readFileSync(apkPath)).digest("hex")
    : null;
  const size = apkPath && existsSync(apkPath) ? statSync(apkPath).size : null;
  const lines = [`DSH Mobile ${version}（versionCode ${versionCodeOf(version)}）`, ""];

  lines.push("Android 客户端，远程操控桌面端 DeepSeek Harness。包名 `com.qgx1992.dshmobile`，可与上游签名版并存。", "");

  if (size !== null) {
    lines.push(
      `- 包体：${(size / 1024 / 1024).toFixed(1)} MB（${bytesText(size)} B）`,
      `- sha256：\`${sha256}\``,
      "- 与仓库根目录 keystore.properties 同一把签名，可直接覆盖升级",
    );
    if (lanUrl) lines.push(`- 局域网下载：${lanUrl}`);
    lines.push("");
  }

  if (tagBody) {
    lines.push("---", "", tagBody, "");
  }
  if (commits) {
    lines.push("### 本版提交", "", commits, "");
  }
  lines.push("---", "", "APK 由 `scripts/publish-apk.mjs` 发布：先落到局域网下载站（含 SHA256SUMS.txt），再挂到这里。");
  return lines.join("\n");
}

/** 建 Release，或给已存在的 Release 补资产/改说明。返回 true 表示成功。 */
export function publishRelease({ version, tag, apkPath, notes, title, latest = false, attach = true }) {
  const slug = repoSlug();
  const tmp = mkdtempSync(path.join(tmpdir(), "dsh-release-"));
  const notesFile = path.join(tmp, "notes.md");
  writeFileSync(notesFile, notes.endsWith("\n") ? notes : `${notes}\n`, "utf8");

  try {
    if (releaseExists(tag)) {
      console.log(`· Release ${tag} 已存在，更新说明${attach ? "并补资产" : ""}`);
      const edit = run("gh", ["release", "edit", tag, "--repo", slug, "--title", title, "--notes-file", notesFile]);
      if (edit.status !== 0) {
        console.error(edit.stderr.trim());
        return false;
      }
      if (attach && apkPath) {
        const upload = run("gh", ["release", "upload", tag, apkPath, "--repo", slug, "--clobber"]);
        if (upload.status !== 0) {
          console.error(upload.stderr.trim());
          return false;
        }
      }
    } else {
      const args = ["release", "create", tag, "--repo", slug, "--title", title, "--notes-file", notesFile];
      if (latest) args.push("--latest");
      // tag 还没推上去时（先发下载站、后打 tag 的流程），让 gh 就地建在这个提交上
      if (!tagExists(tag)) {
        args.push("--target", run("git", ["rev-parse", "HEAD"]).stdout.trim());
      }
      if (attach && apkPath) args.push(apkPath);
      const create = run("gh", args);
      if (create.status !== 0) {
        console.error(create.stderr.trim());
        return false;
      }
    }
    return true;
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

export function releaseTitle(version, tag) {
  const subject = run("git", ["tag", "--list", tag, "--format=%(contents:subject)"]).stdout.trim();
  const cleaned = subject.replace(/^v?\d+\.\d+\.\d+\s*[—-]?\s*/, "").trim();
  return cleaned && cleaned !== version ? `v${version} — ${cleaned}` : `v${version}`;
}
