#!/usr/bin/env node
/**
 * 把刚打出来的 release APK 发布到局域网下载目录（默认 8080 端口那个站），
 * 并刷新校验清单与下载页上的 DSH Mobile 区块。
 *
 * 用法：
 *   DSH_VERSION_NAME=0.22.5 ./gradlew :app:assembleRelease
 *   node scripts/publish-apk.mjs --version 0.22.5 --note "本次改动：……" --verify
 *
 * 可选参数：
 *   --dir <目录>     下载站根目录（默认取 $DSH_APK_DIR 或 ../PiDeck-Mobile/apk）
 *   --history <文本>  「此前版本：」那一行（默认沿用页面上原有的文字）
 *   --verify          发布后走一遍 HTTP 复核（HEAD 字节数 + 校验清单）
 *   --port <端口>     复核用端口（默认 8080）
 *   --no-release      不建/不更新 GitHub Release（默认会建，见 github-release.mjs）
 *
 * 为什么不是一个 copy 了事：下载站同时挂 PiDeck 的包，校验清单是合并的；
 * 页面上还有「回退到旧版」的链接列表，靠目录里实际存在的 APK 生成，不会指向不存在的文件。
 */

import { copyFileSync, existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { networkInterfaces } from "node:os";
import path from "node:path";

import { publishRelease, releaseNotes, releaseTitle, repoSlug } from "./github-release.mjs";

const REPO = path.resolve(import.meta.dirname, "..");
const SOURCE = path.join(REPO, "app", "build", "outputs", "apk", "release", "app-release.apk");
const DEFAULT_DIR = path.resolve(REPO, "..", "PiDeck-Mobile", "apk");

function arg(name, fallback = null) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const flag = (name) => process.argv.includes(`--${name}`);

const version = arg("version", process.env.DSH_VERSION_NAME ?? null);
if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
  console.error("✗ 需要版本号：--version 0.22.5（或设置 DSH_VERSION_NAME）");
  process.exit(1);
}
const note = arg("note", "");
const targetDir = path.resolve(arg("dir", process.env.DSH_APK_DIR ?? DEFAULT_DIR));
const fileName = `DSH-Mobile-${version}-release.apk`;
const target = path.join(targetDir, fileName);

if (!existsSync(SOURCE)) {
  console.error(`✗ 找不到构建产物：${SOURCE}\n  先跑 DSH_VERSION_NAME=${version} ./gradlew :app:assembleRelease`);
  process.exit(1);
}
if (!existsSync(targetDir)) {
  console.error(`✗ 下载目录不存在：${targetDir}`);
  process.exit(1);
}

// 版本号与 versionCode 的换算跟 app/build.gradle.kts 保持一致：major*10000 + minor*100 + patch
const [major, minor, patch] = version.split(".").map(Number);
const versionCode = major * 10000 + minor * 100 + patch;

copyFileSync(SOURCE, target);
const bytes = readFileSync(target);
const sha256 = createHash("sha256").update(bytes).digest("hex");
const sizeBytes = statSync(target).size;
const sizeMB = (sizeBytes / 1024 / 1024).toFixed(1);
const sha256Of = (file) => createHash("sha256").update(readFileSync(file)).digest("hex");

// ---- 校验清单：合并而不是覆盖（目录里同时有别的项目的包） ----
const sumsFile = path.join(targetDir, "SHA256SUMS.txt");
const otherLines = existsSync(sumsFile)
  ? readFileSync(sumsFile, "utf8")
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => {
        if (!line) return false;
        const name = (line.split(/\s+/).pop() ?? "").replace(/^\*/, "");
        // 同名条目去重；已经删掉的包不该继续留在清单里
        return name !== fileName && existsSync(path.join(targetDir, name));
      })
  : [];
// 目录里还有没登记的包（别的项目刚发布过，或老版本从没写进清单）→ 顺手补上，
// 否则页面上「校验值见 SHA256SUMS.txt」对那些包就是空话
const listed = new Set(otherLines.map((line) => (line.split(/\s+/).pop() ?? "").replace(/^\*/, "")));
for (const name of readdirSync(targetDir).sort()) {
  if (!name.endsWith(".apk") || listed.has(name) || name === fileName) continue;
  otherLines.push(`${sha256Of(path.join(targetDir, name))}  ${name}`);
}
writeFileSync(sumsFile, `${[...otherLines, `${sha256}  ${fileName}`].join("\n")}\n`, "utf8");

// ---- 网络地址（报告、Release 正文、下载页都要用）----
const lanAddresses = Object.values(networkInterfaces())
  .flat()
  .filter((net) => net && net.family === "IPv4" && !net.internal)
  .map((net) => net.address);
const port = arg("port", "8080");
const lanUrl = lanAddresses.length ? `http://${lanAddresses[0]}:${port}/${fileName}` : null;

// ---- GitHub Release：同一份包挂到仓库 Release 页（下载站是内网，这里是公开入口）----
// 先发 Release 再写下载页：页面上的「GitHub Release」链接只在真的发出去时才出现。
const releaseTag = `v${version}`;
const releaseUrl = `https://github.com/${repoSlug()}/releases/tag/${releaseTag}`;
let releasePublished = false;
if (!flag("no-release")) {
  const title = releaseTitle(version, releaseTag);
  console.log(`GitHub Release ${releaseTag} · ${title}`);
  releasePublished = publishRelease({
    version,
    tag: releaseTag,
    apkPath: target,
    notes: releaseNotes({ version, tag: releaseTag, apkPath: target, lanUrl }),
    title,
    latest: true,
  });
  if (releasePublished) {
    console.log(`  ✓ ${releaseUrl}`);
  } else {
    console.error("! Release 没发出去；下载站那边的发布不受影响");
    process.exitCode = 1;
  }
}

// ---- 下载页：重写 DSH Mobile 那一块 ----
const indexFile = path.join(targetDir, "index.html");
if (existsSync(indexFile)) {
  const html = readFileSync(indexFile, "utf8");
  const start = html.indexOf("<h1>DSH Mobile");
  const end = start >= 0 ? html.indexOf("<ol>", start) : -1;
  if (start >= 0 && end > start) {
    const section = html.slice(start, end);
    const previousHistory =
      /此前版本：([\s\S]*?)<br>/.exec(section)?.[1]?.trim() ?? "";
    const history = arg("history", previousHistory);

    // 回退列表只放目录里真实存在的包，按语义版本倒序，最多 8 个
    const rollbacks = readdirSync(targetDir)
      .map((name) => /^DSH-Mobile-(\d+\.\d+\.\d+)-release\.apk$/.exec(name))
      .filter(Boolean)
      .map((m) => m[1])
      .filter((v) => v !== version)
      .sort((a, b) => {
        const pa = a.split(".").map(Number);
        const pb = b.split(".").map(Number);
        return pb[0] - pa[0] || pb[1] - pa[1] || pb[2] - pa[2];
      })
      .slice(0, 8);

    const rollbackLine = rollbacks.length
      ? `回退：${rollbacks
          .map((v) => `<a href="DSH-Mobile-${v}-release.apk">${v}</a>`)
          .join(" · ")} · 校验值见 <a href="SHA256SUMS.txt">SHA256SUMS.txt</a>`
      : `校验值见 <a href="SHA256SUMS.txt">SHA256SUMS.txt</a>`;

    const replacement = [
      `<h1>DSH Mobile — ${version} (release)</h1>`,
      `<a class="btn" href="${fileName}" download>下载 DSH Mobile APK</a>`,
      `<div class="meta">com.qgx1992.dshmobile · ${sizeMB} MB · 与仓库同一把 keystore 签名 · versionCode ${versionCode}<br>`,
      `同一份二进制，可从 ${rollbacks[0] ?? "上一版"} 直接覆盖升级${note ? "<br>" : ""}`,
      note ? `${note}<br>` : "",
      history ? `此前版本：${history}<br>` : "",
      releasePublished ? `公开发布：<a href="${releaseUrl}">GitHub Release ${releaseTag}</a><br>` : "",
      `${rollbackLine}</div>`,
      "",
    ].join("\n");

    writeFileSync(indexFile, html.slice(0, start) + replacement + html.slice(end), "utf8");
    console.log(`✓ index.html 区块已更新（回退列表 ${rollbacks.length} 个）`);
  } else {
    console.warn("! index.html 里没找到 DSH Mobile 区块，跳过页面更新");
  }
}

// ---- 报告 ----
console.log(`✓ ${target}`);
console.log(`  ${sizeMB} MB (${sizeBytes} B)  sha256=${sha256}  versionCode=${versionCode}`);
for (const address of ["127.0.0.1", ...lanAddresses]) {
  console.log(`  http://${address}:${arg("port", "8080")}/${fileName}`);
}

// ---- 可选：走一遍真 HTTP 复核 ----
if (flag("verify")) {
  const origin = `http://127.0.0.1:${arg("port", "8080")}`;
  const head = await fetch(`${origin}/${encodeURIComponent(fileName)}`, { method: "HEAD" });
  const remoteSize = Number(head.headers.get("content-length"));
  const sums = await (await fetch(`${origin}/SHA256SUMS.txt`)).text();
  const page = await (await fetch(`${origin}/`)).text();
  console.log(`\n复核 ${origin}`);
  console.log(`  HEAD ${head.status}  content-length=${remoteSize} ${remoteSize === sizeBytes ? "= 本地" : "≠ 本地！"}`);
  console.log(`  SHA256SUMS.txt 含本包：${sums.includes(fileName) ? "是" : "否！"}`);
  console.log(`  首页指向 ${version}：${page.includes(`DSH-Mobile-${version}-release.apk`) ? "是" : "否！"}`);
  if (head.status !== 200 || remoteSize !== sizeBytes) process.exitCode = 1;
}

