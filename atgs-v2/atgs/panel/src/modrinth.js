// ==============================================================
//  ATGS — Modrinth API Client
//  Uses native Node.js https — no curl dependency for search.
//  Uses curl only for file downloads (streaming large files).
// ==============================================================
const https = require('https');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const BASE = 'https://api.modrinth.com/v2';
const UA = 'atgs-panel/1.0 (xkstudios)';

// Native HTTPS GET — returns parsed JSON
function apiGet(endpoint) {
  return new Promise((resolve, reject) => {
    const url = `${BASE}${endpoint}`;
    const req = https.get(url, { headers: { 'User-Agent': UA } }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        // Follow redirect
        https.get(res.headers.location, { headers: { 'User-Agent': UA } }, res2 => {
          let data = '';
          res2.on('data', c => (data += c));
          res2.on('end', () => {
            try { resolve(JSON.parse(data)); }
            catch (e) { reject(new Error(`JSON parse error: ${data.slice(0, 200)}`)); }
          });
        }).on('error', reject);
        return;
      }
      if (res.statusCode !== 200) {
        let body = '';
        res.on('data', c => (body += c));
        res.on('end', () => reject(new Error(`Modrinth API ${res.statusCode}: ${body.slice(0, 200)}`)));
        return;
      }
      let data = '';
      res.on('data', c => (data += c));
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch (e) { reject(new Error(`JSON parse error: ${data.slice(0, 200)}`)); }
      });
    });
    req.on('error', reject);
    req.setTimeout(15000, () => { req.destroy(); reject(new Error('Modrinth API timeout')); });
  });
}

// Synchronous wrapper for route handlers
function apiGetSync(endpoint) {
  try {
    const data = execSync(
      `curl -sf -H "User-Agent: ${UA}" "${BASE}${endpoint}"`,
      { timeout: 15000, maxBuffer: 5 * 1024 * 1024 }
    ).toString();
    return JSON.parse(data);
  } catch (e) {
    throw new Error(`Modrinth API failed: ${e.message}`);
  }
}

// Search mods/plugins
function search(query, opts = {}) {
  const facets = [];
  if (opts.loader) facets.push(`["categories:${opts.loader}"]`);
  if (opts.gameVersion) facets.push(`["versions:${opts.gameVersion}"]`);
  if (opts.projectType) facets.push(`["project_type:${opts.projectType}"]`);

  let url = `/search?query=${encodeURIComponent(query || '')}&limit=20`;
  if (facets.length) url += `&facets=${encodeURIComponent('[' + facets.join(',') + ']')}`;

  return apiGetSync(url);
}

// Get project details
function getProject(slugOrId) {
  return apiGetSync(`/project/${encodeURIComponent(slugOrId)}`);
}

// Get versions for a project
function getVersions(slugOrId, opts = {}) {
  let url = `/project/${encodeURIComponent(slugOrId)}/version`;
  const params = [];
  if (opts.loader) params.push(`loaders=${encodeURIComponent('["' + opts.loader + '"]')}`);
  if (opts.gameVersion) params.push(`game_versions=${encodeURIComponent('["' + opts.gameVersion + '"]')}`);
  if (params.length) url += '?' + params.join('&');
  return apiGetSync(url);
}

// Download and install a mod into an instance
function installMod(slug, instanceDir, opts = {}) {
  const loader = opts.loader || 'fabric';
  const gameVersion = opts.gameVersion || '';
  const subDir = opts.subDir || 'mods';

  // Get compatible versions
  const versions = getVersions(slug, { loader, gameVersion });

  if (!versions || !versions.length) {
    throw new Error(`No compatible version of "${slug}" found for ${loader} ${gameVersion}`);
  }

  // Pick the latest compatible version
  const version = versions[0];
  const file = version.files.find(f => f.primary) || version.files[0];

  if (!file || !file.url) {
    throw new Error(`No downloadable file found for "${slug}"`);
  }

  // Create target directory
  const destDir = path.join(instanceDir, subDir);
  fs.mkdirSync(destDir, { recursive: true });

  // Check if already installed (by filename pattern)
  const existing = fs.readdirSync(destDir);
  const slugBase = slug.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
  const alreadyInstalled = existing.some(f => {
    const fBase = f.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
    return fBase.includes(slugBase) || f === file.filename;
  });
  if (alreadyInstalled && !opts.force) {
    return { name: version.name, version: version.version_number, filename: file.filename, skipped: true, reason: 'Already installed' };
  }

  const destPath = path.join(destDir, file.filename);

  // Download the file
  try {
    execSync(`curl -fSL -o "${destPath}" "${file.url}"`, { timeout: 120000 });
  } catch (e) {
    try { fs.unlinkSync(destPath); } catch {}
    throw new Error(`Failed to download "${slug}": ${e.message}`);
  }

  // Verify the file exists and has content
  if (!fs.existsSync(destPath) || fs.statSync(destPath).size === 0) {
    try { fs.unlinkSync(destPath); } catch {}
    throw new Error(`Download of "${slug}" produced an empty file`);
  }

  return {
    name: version.name,
    version: version.version_number,
    filename: file.filename,
    size: fs.statSync(destPath).size,
    dependencies: version.dependencies || [],
  };
}

// Install a mod AND all its required dependencies
function installModWithDeps(slug, instanceDir, opts = {}) {
  const loader = opts.loader || 'fabric';
  const gameVersion = opts.gameVersion || '';
  const subDir = opts.subDir || 'mods';
  const installed = [];
  const visited = new Set();

  function resolve(modSlug) {
    if (visited.has(modSlug)) return;
    visited.add(modSlug);

    try {
      const result = installMod(modSlug, instanceDir, { loader, gameVersion, subDir });
      installed.push(result);

      // Resolve required dependencies
      if (result.dependencies && !result.skipped) {
        for (const dep of result.dependencies) {
          // Only auto-install required dependencies
          if (dep.dependency_type !== 'required') continue;

          // dep.project_id is the Modrinth project ID
          if (dep.project_id) {
            try {
              // Get the project slug from the ID
              const project = getProject(dep.project_id);
              if (project?.slug) {
                resolve(project.slug);
              }
            } catch (e) {
              console.warn(`[Modrinth] Could not resolve dependency ${dep.project_id}: ${e.message}`);
            }
          }
        }
      }
    } catch (e) {
      installed.push({ slug: modSlug, error: e.message });
    }
  }

  resolve(slug);
  return installed;
}

// List installed mods in an instance directory
function listInstalled(instanceDir, subDir = 'mods') {
  const dir = path.join(instanceDir, subDir);
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir)
    .filter(f => f.endsWith('.jar'))
    .map(f => {
      const st = fs.statSync(path.join(dir, f));
      return { filename: f, size: st.size, modified: st.mtime.toISOString() };
    })
    .sort((a, b) => a.filename.localeCompare(b.filename));
}

module.exports = { search, getProject, getVersions, installMod, installModWithDeps, listInstalled };
