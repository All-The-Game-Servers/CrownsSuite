function serializeProperties(existingContent, patch) {
  const lines = String(existingContent || '').split(/\r?\n/);
  const remaining = new Map(Object.entries(patch));
  const output = lines.map((line) => {
    const idx = line.indexOf('=');
    if (idx === -1) return line;
    const key = line.slice(0, idx);
    if (!remaining.has(key)) return line;
    const value = remaining.get(key);
    remaining.delete(key);
    return `${key}=${value}`;
  });

  for (const [key, value] of remaining) {
    output.push(`${key}=${value}`);
  }

  return output.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd() + '\n';
}

module.exports = {
  serializeProperties
};
